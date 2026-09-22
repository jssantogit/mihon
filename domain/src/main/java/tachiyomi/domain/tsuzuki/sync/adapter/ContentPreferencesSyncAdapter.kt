package tachiyomi.domain.tsuzuki.sync.adapter

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.content.ContentPreference
import tachiyomi.domain.tsuzuki.content.repository.ContentPreferenceRepository
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreferences
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncRecordEnvelope
import tachiyomi.domain.tsuzuki.sync.service.SyncClock
import tachiyomi.domain.tsuzuki.sync.service.SyncDocumentAdapter
import tachiyomi.domain.tsuzuki.sync.service.SyncRevisionSource

class ContentPreferencesSyncAdapter(
    private val repository: ContentPreferenceRepository,
    private val readerPreferences: CanonicalReaderPreferences,
    private val revisionSource: SyncRevisionSource,
    private val clock: SyncClock,
) : SyncDocumentAdapter {

    override val documentKind = SyncDocumentKind.CONTENT_PREFERENCES

    override suspend fun exportDocument(): SyncDocumentEnvelope {
        val records = linkedMapOf<String, SyncRecordEnvelope>()

        repository.getAll()
            .sortedBy(ContentPreference::canonicalTitleId)
            .forEach { preference ->
                val recordId = titleRecordId(preference.canonicalTitleId)
                records[recordId] = SyncRecordEnvelope(
                    id = recordId,
                    revision = revisionSource.nextRevision(),
                    updatedAtEpochMillis = preference.updatedAt,
                    fields = buildJsonObject {
                        put("recordType", RECORD_TYPE_TITLE)
                        put("canonicalTitleId", preference.canonicalTitleId)
                        put(
                            "preferredAddonId",
                            preference.preferredAddonId
                                ?.value
                                ?.let(::JsonPrimitive)
                                ?: JsonNull,
                        )
                        put(
                            "preferredLanguage",
                            preference.preferredLanguage?.let(::JsonPrimitive) ?: JsonNull,
                        )
                    },
                )
            }

        val now = clock.nowEpochMillis()
        records[GLOBAL_RECORD_ID] = SyncRecordEnvelope(
            id = GLOBAL_RECORD_ID,
            revision = revisionSource.nextRevision(),
            updatedAtEpochMillis = now,
            fields = buildJsonObject {
                put("recordType", RECORD_TYPE_GLOBAL)
                put("automaticFallback", readerPreferences.automaticFallback.get())
                put(
                    "preferredLanguages",
                    JsonArray(
                        readerPreferences.preferredLanguages.get()
                            .distinct()
                            .map(::JsonPrimitive),
                    ),
                )
            },
        )

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
            "Content preferences adapter cannot apply ${document.kind}"
        }

        document.records.values
            .sortedBy(SyncRecordEnvelope::id)
            .forEach { record ->
                if (record.id == GLOBAL_RECORD_ID) {
                    applyGlobal(record)
                } else {
                    applyTitle(record)
                }
            }
    }

    private suspend fun applyTitle(record: SyncRecordEnvelope) {
        val canonicalTitleId = record.fields["canonicalTitleId"]
            ?.jsonPrimitive
            ?.content
            ?: titleIdFromRecord(record.id)

        if (record.isTombstone) {
            repository.delete(canonicalTitleId)
            return
        }

        val addonElement = record.fields.getValue("preferredAddonId")
        val preferredAddonId = if (addonElement is JsonNull) {
            null
        } else {
            AddonId(addonElement.jsonPrimitive.content)
        }

        val languageElement = record.fields["preferredLanguage"]
        val preferredLanguage = if (languageElement == null || languageElement is JsonNull) {
            null // Older sync documents do not contain this field.
        } else {
            languageElement.jsonPrimitive.content.trim().takeIf(String::isNotEmpty)
        }

        repository.upsert(
            ContentPreference(
                canonicalTitleId = canonicalTitleId,
                preferredAddonId = preferredAddonId,
                preferredLanguage = preferredLanguage,
                updatedAt = record.updatedAtEpochMillis,
            ),
        )
    }

    private fun applyGlobal(record: SyncRecordEnvelope) {
        if (record.isTombstone) {
            readerPreferences.automaticFallback.set(false)
            readerPreferences.preferredLanguages.set(emptyList())
            return
        }

        readerPreferences.automaticFallback.set(
            record.fields.getValue("automaticFallback").jsonPrimitive.boolean,
        )
        readerPreferences.preferredLanguages.set(
            record.fields.getValue("preferredLanguages")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct(),
        )
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val GLOBAL_RECORD_ID = "global"
        const val RECORD_TYPE_TITLE = "title"
        const val RECORD_TYPE_GLOBAL = "global"
        const val TITLE_PREFIX = "title:"
    }
}

private fun titleRecordId(canonicalTitleId: String): String =
    "title:$canonicalTitleId"

private fun titleIdFromRecord(recordId: String): String {
    require(recordId.startsWith("title:")) {
        "Content preference record ID must use title prefix"
    }
    return recordId.removePrefix("title:").also {
        require(it.isNotBlank()) {
            "Content preference record must contain canonical title ID"
        }
    }
}
