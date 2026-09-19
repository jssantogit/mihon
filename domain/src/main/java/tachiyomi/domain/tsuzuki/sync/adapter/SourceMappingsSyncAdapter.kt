package tachiyomi.domain.tsuzuki.sync.adapter

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import tachiyomi.domain.tsuzuki.source.repository.ReadingSourcePreferenceRepository
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncRecordEnvelope
import tachiyomi.domain.tsuzuki.sync.service.SyncClock
import tachiyomi.domain.tsuzuki.sync.service.SyncDocumentAdapter
import tachiyomi.domain.tsuzuki.sync.service.SyncRevisionSource

class SourceMappingsSyncAdapter(
    private val mappingRepository: SourceTitleMappingRepository,
    private val preferenceRepository: ReadingSourcePreferenceRepository,
    private val titleRepository: CanonicalTitleRepository,
    private val revisionSource: SyncRevisionSource,
    private val clock: SyncClock,
) : SyncDocumentAdapter {

    override val documentKind = SyncDocumentKind.SOURCE_MAPPINGS

    override suspend fun exportDocument(): SyncDocumentEnvelope {
        val now = clock.nowEpochMillis()
        val records = linkedMapOf<String, SyncRecordEnvelope>()

        mappingRepository.getAll()
            .sortedWith(compareBy(SourceTitleMapping::sourceId, SourceTitleMapping::sourceUrl))
            .forEach { mapping ->
                val title = checkNotNull(titleRepository.getById(mapping.canonicalTitleId)) {
                    "Source mapping ${mapping.id} points to a missing canonical title"
                }
                val recordId = sourceMappingSyncRecordId(mapping.sourceId, mapping.sourceUrl)
                records[recordId] = SyncRecordEnvelope(
                    id = recordId,
                    revision = revisionSource.nextRevision(),
                    updatedAtEpochMillis = mapping.updatedAt,
                    fields = buildJsonObject {
                        put("recordType", RECORD_TYPE_MAPPING)
                        put("canonicalTitleId", mapping.canonicalTitleId)
                        put("titleDisplayTitle", title.displayTitle)
                        put("titleIdentityState", title.identityState.name)
                        put("titleCreatedAt", title.createdAt)
                        put("titleUpdatedAt", title.updatedAt)
                        put("sourceId", mapping.sourceId)
                        put("sourceUrl", mapping.sourceUrl)
                        put("language", mapping.language)
                        put(
                            "matchConfidence",
                            mapping.matchConfidence?.let(::JsonPrimitive) ?: JsonNull,
                        )
                        put("verifiedByUser", mapping.verifiedByUser)
                        put("availability", mapping.availability.name)
                        put("preferredOverride", mapping.preferredOverride)
                        put("createdAt", mapping.createdAt)
                        put("updatedAt", mapping.updatedAt)
                    },
                )
            }

        preferenceRepository.getConfiguredLanguages()
            .sorted()
            .forEach { language ->
                val preferences = preferenceRepository.getForLanguage(language)
                    .sortedBy { it.position }
                val recordId = sourcePreferenceSyncRecordId(language)
                records[recordId] = SyncRecordEnvelope(
                    id = recordId,
                    revision = revisionSource.nextRevision(),
                    updatedAtEpochMillis = 0,
                    fields = buildJsonObject {
                        put("recordType", RECORD_TYPE_PREFERENCE)
                        put("language", language)
                        put(
                            "sourceIds",
                            JsonArray(preferences.map { JsonPrimitive(it.sourceId) }),
                        )
                    },
                )
            }

        return SyncDocumentEnvelope(
            schemaVersion = SCHEMA_VERSION,
            kind = documentKind,
            revision = revisionSource.nextRevision(),
            generatedAtEpochMillis = now,
            records = records,
        )
    }

    override suspend fun applyDocument(document: SyncDocumentEnvelope) {
        require(document.kind == documentKind) {
            "Source mappings adapter cannot apply ${document.kind}"
        }

        val parsed = document.records.values
            .sortedBy(SyncRecordEnvelope::id)
            .map(::parseRecord)

        parsed.filterIsInstance<ParsedSourceRecord.Mapping>()
            .filterNot { it.deleted }
            .filter { it.preferredOverride }
            .groupBy { it.canonicalTitleId }
            .values
            .forEach { preferred ->
                require(preferred.size <= 1) {
                    "A canonical title cannot have multiple preferred source mappings"
                }
            }

        parsed.forEach { record ->
            when (record) {
                is ParsedSourceRecord.Mapping -> applyMapping(record)
                is ParsedSourceRecord.Preference -> {
                    preferenceRepository.replaceForLanguage(
                        language = record.language,
                        orderedSourceIds = if (record.deleted) emptyList() else record.sourceIds,
                    )
                }
            }
        }
    }

    private suspend fun applyMapping(record: ParsedSourceRecord.Mapping) {
        val existing = mappingRepository.getBySource(
            sourceId = record.sourceId,
            sourceUrl = record.sourceUrl,
        )

        if (record.deleted) {
            existing?.let { mappingRepository.remove(it.id) }
            return
        }

        titleRepository.upsert(
            CanonicalTitle(
                id = record.canonicalTitleId,
                displayTitle = record.titleDisplayTitle,
                identityState = record.titleIdentityState,
                createdAt = record.titleCreatedAt,
                updatedAt = record.titleUpdatedAt,
            ),
        )

        mappingRepository.upsert(
            SourceTitleMapping(
                id = existing?.id ?: record.syncRecordId,
                canonicalTitleId = record.canonicalTitleId,
                mihonMangaId = existing?.mihonMangaId,
                sourceId = record.sourceId,
                sourceUrl = record.sourceUrl,
                language = record.language,
                matchConfidence = record.matchConfidence,
                verifiedByUser = record.verifiedByUser,
                availability = record.availability,
                preferredOverride = record.preferredOverride,
                createdAt = record.createdAt,
                updatedAt = record.updatedAt,
            ),
        )
    }

    private fun parseRecord(record: SyncRecordEnvelope): ParsedSourceRecord {
        val fields = record.fields
        return when (fields.requiredString("recordType")) {
            RECORD_TYPE_MAPPING -> ParsedSourceRecord.Mapping(
                syncRecordId = record.id,
                deleted = record.isTombstone,
                canonicalTitleId = fields.requiredString("canonicalTitleId"),
                titleDisplayTitle = fields.requiredString("titleDisplayTitle"),
                titleIdentityState = CanonicalIdentityState.valueOf(
                    fields.requiredString("titleIdentityState"),
                ),
                titleCreatedAt = fields.requiredLong("titleCreatedAt"),
                titleUpdatedAt = fields.requiredLong("titleUpdatedAt"),
                sourceId = fields.requiredLong("sourceId"),
                sourceUrl = fields.requiredString("sourceUrl"),
                language = fields.requiredString("language"),
                matchConfidence = fields.optionalDouble("matchConfidence"),
                verifiedByUser = fields.requiredBoolean("verifiedByUser"),
                availability = SourceMappingAvailability.valueOf(
                    fields.requiredString("availability"),
                ),
                preferredOverride = fields.requiredBoolean("preferredOverride"),
                createdAt = fields.requiredLong("createdAt"),
                updatedAt = fields.requiredLong("updatedAt"),
            )

            RECORD_TYPE_PREFERENCE -> ParsedSourceRecord.Preference(
                deleted = record.isTombstone,
                language = fields.requiredString("language"),
                sourceIds = fields.getValue("sourceIds")
                    .jsonArray
                    .map { it.jsonPrimitive.long },
            )

            else -> error("Unknown source sync record type")
        }
    }

    private sealed interface ParsedSourceRecord {
        data class Mapping(
            val syncRecordId: String,
            val deleted: Boolean,
            val canonicalTitleId: String,
            val titleDisplayTitle: String,
            val titleIdentityState: CanonicalIdentityState,
            val titleCreatedAt: Long,
            val titleUpdatedAt: Long,
            val sourceId: Long,
            val sourceUrl: String,
            val language: String,
            val matchConfidence: Double?,
            val verifiedByUser: Boolean,
            val availability: SourceMappingAvailability,
            val preferredOverride: Boolean,
            val createdAt: Long,
            val updatedAt: Long,
        ) : ParsedSourceRecord

        data class Preference(
            val deleted: Boolean,
            val language: String,
            val sourceIds: List<Long>,
        ) : ParsedSourceRecord
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val RECORD_TYPE_MAPPING = "mapping"
        const val RECORD_TYPE_PREFERENCE = "preference"
    }
}

internal fun sourceMappingSyncRecordId(sourceId: Long, sourceUrl: String): String =
    "mapping:${sourceId}:${sourceUrl}"

internal fun sourcePreferenceSyncRecordId(language: String): String =
    "preference:${language}"

private fun JsonObject.requiredString(name: String): String =
    getValue(name).jsonPrimitive.content

private fun JsonObject.requiredLong(name: String): Long =
    getValue(name).jsonPrimitive.long

private fun JsonObject.requiredBoolean(name: String): Boolean =
    getValue(name).jsonPrimitive.boolean

private fun JsonObject.optionalDouble(name: String): Double? {
    val value = getValue(name)
    return if (value is JsonNull) null else value.jsonPrimitive.double
}
