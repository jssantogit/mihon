package tachiyomi.domain.tsuzuki.sync.adapter

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import tachiyomi.domain.tsuzuki.chapter.model.ChapterOverride
import tachiyomi.domain.tsuzuki.chapter.model.ChapterOverrideKind
import tachiyomi.domain.tsuzuki.chapter.repository.ChapterOverrideRepository
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreference
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReaderPreferenceRepository
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncRecordEnvelope
import tachiyomi.domain.tsuzuki.sync.service.SyncClock
import tachiyomi.domain.tsuzuki.sync.service.SyncDocumentAdapter
import tachiyomi.domain.tsuzuki.sync.service.SyncRevisionSource

class ChapterOverridesSyncAdapter(
    private val overrideRepository: ChapterOverrideRepository,
    private val readerPreferenceRepository: CanonicalReaderPreferenceRepository,
    private val revisionSource: SyncRevisionSource,
    private val clock: SyncClock,
) : SyncDocumentAdapter {

    override val documentKind = SyncDocumentKind.CHAPTER_OVERRIDES

    override suspend fun exportDocument(): SyncDocumentEnvelope {
        val records = linkedMapOf<String, SyncRecordEnvelope>()

        overrideRepository.getAll(includeDeleted = true)
            .sortedBy(ChapterOverride::id)
            .forEach { chapterOverride ->
                val recordId = chapterOverrideSyncRecordId(chapterOverride.id)
                records[recordId] = SyncRecordEnvelope(
                    id = recordId,
                    revision = revisionSource.nextRevision(),
                    updatedAtEpochMillis = chapterOverride.updatedAt,
                    deletedAtEpochMillis = chapterOverride.deletedAt,
                    fields = buildJsonObject {
                        put("recordType", RECORD_TYPE_OVERRIDE)
                        put("canonicalTitleId", chapterOverride.canonicalTitleId)
                        put(
                            "canonicalChapterKey",
                            chapterOverride.canonicalChapterKey?.let(::JsonPrimitive) ?: JsonNull,
                        )
                        put("sourceId", chapterOverride.sourceId?.let(::JsonPrimitive) ?: JsonNull)
                        put(
                            "sourceTitleUrl",
                            chapterOverride.sourceTitleUrl?.let(::JsonPrimitive) ?: JsonNull,
                        )
                        put(
                            "sourceChapterId",
                            chapterOverride.sourceChapterId?.let(::JsonPrimitive) ?: JsonNull,
                        )
                        put("kind", chapterOverride.kind.name)
                        put("payloadJson", chapterOverride.payloadJson)
                        put("schemaVersion", chapterOverride.schemaVersion)
                        put("createdAt", chapterOverride.createdAt)
                    },
                )
            }

        readerPreferenceRepository.getAll()
            .sortedBy(CanonicalReaderPreference::canonicalTitleId)
            .forEach { preference ->
                val recordId = readerPreferenceSyncRecordId(preference.canonicalTitleId)
                records[recordId] = SyncRecordEnvelope(
                    id = recordId,
                    revision = revisionSource.nextRevision(),
                    updatedAtEpochMillis = preference.updatedAt,
                    fields = buildJsonObject {
                        put("recordType", RECORD_TYPE_READER_PREFERENCE)
                        put("canonicalTitleId", preference.canonicalTitleId)
                        put("automaticFallback", preference.automaticFallback)
                    },
                )
            }

        return SyncDocumentEnvelope(
            schemaVersion = SCHEMA_VERSION,
            kind = documentKind,
            revision = revisionSource.nextRevision(),
            generatedAtEpochMillis = clock.nowEpochMillis(),
            records = records,
        )
    }

    override suspend fun applyDocument(document: SyncDocumentEnvelope) {
        require(document.kind == documentKind) {
            "Chapter overrides adapter cannot apply ${document.kind}"
        }

        document.records.values
            .sortedBy(SyncRecordEnvelope::id)
            .map(::parseRecord)
            .forEach { record ->
                when (record) {
                    is ParsedChapterOverrideRecord.Override -> applyOverride(record)
                    is ParsedChapterOverrideRecord.ReaderPreference -> applyReaderPreference(record)
                }
            }
    }

    private suspend fun applyOverride(record: ParsedChapterOverrideRecord.Override) {
        val existing = overrideRepository.getById(record.id)
        overrideRepository.upsert(
            ChapterOverride(
                id = record.id,
                canonicalTitleId = record.canonicalTitleId,
                canonicalChapterKey = record.canonicalChapterKey,
                sourceId = record.sourceId,
                sourceTitleUrl = record.sourceTitleUrl,
                sourceChapterId = record.sourceChapterId,
                kind = record.kind,
                payloadJson = record.payloadJson,
                schemaVersion = record.schemaVersion,
                revision = existing?.revision?.plus(1) ?: 0,
                createdAt = existing?.createdAt ?: record.createdAt,
                updatedAt = record.updatedAt,
                deletedAt = record.deletedAt,
            ),
        )
    }

    private suspend fun applyReaderPreference(
        record: ParsedChapterOverrideRecord.ReaderPreference,
    ) {
        if (record.deleted) {
            readerPreferenceRepository.delete(record.canonicalTitleId)
            return
        }

        readerPreferenceRepository.upsert(
            CanonicalReaderPreference(
                canonicalTitleId = record.canonicalTitleId,
                automaticFallback = record.automaticFallback,
                updatedAt = record.updatedAt,
            ),
        )
    }

    private fun parseRecord(record: SyncRecordEnvelope): ParsedChapterOverrideRecord {
        val fields = record.fields
        return when (fields.requiredString("recordType")) {
            RECORD_TYPE_OVERRIDE -> ParsedChapterOverrideRecord.Override(
                id = overrideIdFromRecord(record.id),
                canonicalTitleId = fields.requiredString("canonicalTitleId"),
                canonicalChapterKey = fields.optionalString("canonicalChapterKey"),
                sourceId = fields.optionalLong("sourceId"),
                sourceTitleUrl = fields.optionalString("sourceTitleUrl"),
                sourceChapterId = fields.optionalString("sourceChapterId"),
                kind = ChapterOverrideKind.valueOf(fields.requiredString("kind")),
                payloadJson = fields.requiredString("payloadJson"),
                schemaVersion = fields.requiredLong("schemaVersion").toInt(),
                createdAt = fields.requiredLong("createdAt"),
                updatedAt = record.updatedAtEpochMillis,
                deletedAt = record.deletedAtEpochMillis,
            )

            RECORD_TYPE_READER_PREFERENCE -> ParsedChapterOverrideRecord.ReaderPreference(
                deleted = record.isTombstone,
                canonicalTitleId = fields.requiredString("canonicalTitleId"),
                automaticFallback = fields.requiredBoolean("automaticFallback"),
                updatedAt = record.updatedAtEpochMillis,
            )

            else -> error("Unknown chapter override sync record type")
        }
    }

    private sealed interface ParsedChapterOverrideRecord {
        data class Override(
            val id: String,
            val canonicalTitleId: String,
            val canonicalChapterKey: String?,
            val sourceId: Long?,
            val sourceTitleUrl: String?,
            val sourceChapterId: String?,
            val kind: ChapterOverrideKind,
            val payloadJson: String,
            val schemaVersion: Int,
            val createdAt: Long,
            val updatedAt: Long,
            val deletedAt: Long?,
        ) : ParsedChapterOverrideRecord

        data class ReaderPreference(
            val deleted: Boolean,
            val canonicalTitleId: String,
            val automaticFallback: Boolean,
            val updatedAt: Long,
        ) : ParsedChapterOverrideRecord
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val RECORD_TYPE_OVERRIDE = "override"
        const val RECORD_TYPE_READER_PREFERENCE = "reader_preference"
        const val OVERRIDE_PREFIX = "override:"
        const val READER_PREFERENCE_PREFIX = "reader-preference:"
    }
}

internal fun chapterOverrideSyncRecordId(id: String): String = "override:$id"

internal fun readerPreferenceSyncRecordId(canonicalTitleId: String): String =
    "reader-preference:$canonicalTitleId"

private fun overrideIdFromRecord(recordId: String): String =
    recordId.removeRequiredPrefix("override:")

private fun String.removeRequiredPrefix(prefix: String): String {
    require(startsWith(prefix)) { "Sync record ID $this must start with $prefix" }
    return removePrefix(prefix).also {
        require(it.isNotBlank()) { "Sync record ID $this must contain an entity ID" }
    }
}

private fun JsonObject.requiredString(name: String): String =
    getValue(name).jsonPrimitive.content

private fun JsonObject.requiredLong(name: String): Long =
    getValue(name).jsonPrimitive.long

private fun JsonObject.requiredBoolean(name: String): Boolean =
    getValue(name).jsonPrimitive.boolean

private fun JsonObject.optionalString(name: String): String? {
    val value = getValue(name)
    return if (value is JsonNull) null else value.jsonPrimitive.content
}

private fun JsonObject.optionalLong(name: String): Long? {
    val value = getValue(name)
    return if (value is JsonNull) null else value.jsonPrimitive.long
}
