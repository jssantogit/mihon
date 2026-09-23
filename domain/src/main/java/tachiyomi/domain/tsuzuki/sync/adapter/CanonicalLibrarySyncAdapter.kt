package tachiyomi.domain.tsuzuki.sync.adapter

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncRecordEnvelope
import tachiyomi.domain.tsuzuki.sync.service.SyncClock
import tachiyomi.domain.tsuzuki.sync.service.SyncDocumentAdapter
import tachiyomi.domain.tsuzuki.sync.service.SyncRevisionSource

class CanonicalLibrarySyncAdapter(
    private val libraryRepository: CanonicalLibraryRepository,
    private val revisionSource: SyncRevisionSource,
    private val clock: SyncClock,
) : SyncDocumentAdapter {

    override val documentKind = SyncDocumentKind.LIBRARY

    override suspend fun exportDocument(): SyncDocumentEnvelope {
        val now = clock.nowEpochMillis()
        val records = libraryRepository.getAllItemsAsFlow()
            .first()
            .sortedBy { it.entry.canonicalTitleId }
            .associate { item ->
                val entry = item.entry
                entry.canonicalTitleId to SyncRecordEnvelope(
                    id = entry.canonicalTitleId,
                    revision = revisionSource.nextRevision(),
                    updatedAtEpochMillis = entry.updatedAt,
                    fields = buildJsonObject {
                        put("status", entry.status.name)
                        put("favorite", entry.favorite)
                        put("addedAt", entry.addedAt)
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
            "Library adapter cannot apply ${document.kind}"
        }

        document.records.values
            .sortedBy(SyncRecordEnvelope::id)
            .map(::parseRecord)
            .forEach { item ->
                when (item) {
                    is ParsedLibraryRecord.Deleted -> {
                        libraryRepository.remove(item.canonicalTitleId)
                    }
                    is ParsedLibraryRecord.Active -> {
                        libraryRepository.upsert(item.entry)
                    }
                }
            }
    }

    private fun parseRecord(record: SyncRecordEnvelope): ParsedLibraryRecord {
        if (record.isTombstone) {
            return ParsedLibraryRecord.Deleted(record.id)
        }

        val fields = record.fields
        val entry = CanonicalLibraryEntry(
            canonicalTitleId = record.id,
            status = LibraryStatus.valueOf(fields.requiredString("status")),
            favorite = fields.requiredBoolean("favorite"),
            addedAt = fields.requiredLong("addedAt"),
            updatedAt = record.updatedAtEpochMillis,
        )
        return ParsedLibraryRecord.Active(entry)
    }

    private sealed interface ParsedLibraryRecord {
        data class Active(
            val entry: CanonicalLibraryEntry,
        ) : ParsedLibraryRecord

        data class Deleted(
            val canonicalTitleId: String,
        ) : ParsedLibraryRecord
    }

    private companion object {
        const val SCHEMA_VERSION = 2
    }
}

private fun JsonObject.requiredString(name: String): String =
    getValue(name).jsonPrimitive.content

private fun JsonObject.requiredLong(name: String): Long =
    getValue(name).jsonPrimitive.long

private fun JsonObject.requiredBoolean(name: String): Boolean =
    getValue(name).jsonPrimitive.boolean
