package tachiyomi.domain.tsuzuki.sync.adapter

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import tachiyomi.domain.tsuzuki.home.repository.ContinueReadingVisibilityRepository
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncRecordEnvelope
import tachiyomi.domain.tsuzuki.sync.service.SyncClock
import tachiyomi.domain.tsuzuki.sync.service.SyncDocumentAdapter
import tachiyomi.domain.tsuzuki.sync.service.SyncRevisionSource

class ContinueReadingStateSyncAdapter(
    private val visibilityRepository: ContinueReadingVisibilityRepository,
    private val revisionSource: SyncRevisionSource,
    private val clock: SyncClock,
) : SyncDocumentAdapter {

    override val documentKind = SyncDocumentKind.CONTINUE_READING_STATE

    override suspend fun exportDocument(): SyncDocumentEnvelope {
        val records = visibilityRepository.getAll()
            .sortedBy { it.canonicalTitleId }
            .associate { visibility ->
                visibility.canonicalTitleId to SyncRecordEnvelope(
                    id = visibility.canonicalTitleId,
                    revision = revisionSource.nextRevision(),
                    updatedAtEpochMillis = visibility.hiddenAt ?: 0L,
                    fields = buildJsonObject {
                        put(
                            "hiddenAt",
                            visibility.hiddenAt?.let(::JsonPrimitive) ?: JsonNull,
                        )
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
            "Continue Reading state adapter cannot apply ${document.kind}"
        }

        document.records.values
            .sortedBy(SyncRecordEnvelope::id)
            .forEach { record ->
                if (record.isTombstone) {
                    visibilityRepository.clear(record.id)
                    return@forEach
                }

                val hiddenAt = record.fields.optionalLong("hiddenAt")
                if (hiddenAt == null) {
                    visibilityRepository.clear(record.id)
                } else {
                    visibilityRepository.hide(
                        canonicalTitleId = record.id,
                        hiddenAt = hiddenAt,
                    )
                }
            }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}

private fun JsonObject.optionalLong(name: String): Long? {
    val value = getValue(name)
    return if (value is JsonNull) null else value.jsonPrimitive.long
}
