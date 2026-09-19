package tachiyomi.domain.tsuzuki.sync.adapter

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncRecordEnvelope
import tachiyomi.domain.tsuzuki.sync.service.SyncClock
import tachiyomi.domain.tsuzuki.sync.service.SyncDocumentAdapter
import tachiyomi.domain.tsuzuki.sync.service.SyncRevisionSource

class CanonicalLibrarySyncAdapter(
    private val libraryRepository: CanonicalLibraryRepository,
    private val titleRepository: CanonicalTitleRepository,
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
                val title = item.title
                entry.canonicalTitleId to SyncRecordEnvelope(
                    id = entry.canonicalTitleId,
                    revision = revisionSource.nextRevision(),
                    updatedAtEpochMillis = maxOf(entry.updatedAt, title.updatedAt),
                    fields = buildJsonObject {
                        put("status", entry.status.name)
                        put("favorite", entry.favorite)
                        put("addedAt", entry.addedAt)
                        put("updatedAt", entry.updatedAt)
                        put("displayTitle", title.displayTitle)
                        put("identityState", title.identityState.name)
                        put("titleCreatedAt", title.createdAt)
                        put("titleUpdatedAt", title.updatedAt)
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
                        titleRepository.upsert(item.title)
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
        val title = CanonicalTitle(
            id = record.id,
            displayTitle = fields.requiredString("displayTitle"),
            identityState = CanonicalIdentityState.valueOf(
                fields.requiredString("identityState"),
            ),
            createdAt = fields.requiredLong("titleCreatedAt"),
            updatedAt = fields.requiredLong("titleUpdatedAt"),
        )
        val entry = CanonicalLibraryEntry(
            canonicalTitleId = record.id,
            status = LibraryStatus.valueOf(fields.requiredString("status")),
            favorite = fields.requiredBoolean("favorite"),
            addedAt = fields.requiredLong("addedAt"),
            updatedAt = fields.requiredLong("updatedAt"),
        )
        return ParsedLibraryRecord.Active(
            title = title,
            entry = entry,
        )
    }

    private sealed interface ParsedLibraryRecord {
        data class Active(
            val title: CanonicalTitle,
            val entry: CanonicalLibraryEntry,
        ) : ParsedLibraryRecord

        data class Deleted(
            val canonicalTitleId: String,
        ) : ParsedLibraryRecord
    }

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}

private fun JsonObject.requiredString(name: String): String =
    getValue(name).jsonPrimitive.content

private fun JsonObject.requiredLong(name: String): Long =
    getValue(name).jsonPrimitive.long

private fun JsonObject.requiredBoolean(name: String): Boolean =
    getValue(name).jsonPrimitive.boolean
