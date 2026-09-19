package tachiyomi.data.tsuzuki.sync

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import tachiyomi.data.tsuzuki.collections.CollectionQueryJsonCodec
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.collections.model.CollectionFolder
import tachiyomi.domain.tsuzuki.collections.model.CollectionList
import tachiyomi.domain.tsuzuki.collections.model.CollectionOrigin
import tachiyomi.domain.tsuzuki.collections.model.TsuzukiCollection
import tachiyomi.domain.tsuzuki.collections.repository.CollectionStore
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncRecordEnvelope
import tachiyomi.domain.tsuzuki.sync.service.SyncClock
import tachiyomi.domain.tsuzuki.sync.service.SyncDocumentAdapter
import tachiyomi.domain.tsuzuki.sync.service.SyncRevisionSource

class CollectionsSyncAdapter(
    private val store: CollectionStore,
    private val revisionSource: SyncRevisionSource,
    private val clock: SyncClock,
) : SyncDocumentAdapter {

    override val documentKind = SyncDocumentKind.COLLECTIONS

    override suspend fun exportDocument(): SyncDocumentEnvelope {
        val records = linkedMapOf<String, SyncRecordEnvelope>()
        val collections = store.getCollections(includeDeleted = true)
            .filter { it.origin == CollectionOrigin.USER }
            .sortedBy(TsuzukiCollection::id)

        collections.forEach { collection ->
            records[collectionRecordId(collection.id)] = collection.toSyncRecord()

            store.getFolders(collection.id, includeDeleted = true)
                .filter { it.origin == CollectionOrigin.USER }
                .sortedBy(CollectionFolder::id)
                .forEach { folder ->
                    records[folderRecordId(folder.id)] = folder.toSyncRecord()

                    store.getLists(folder.id, includeDeleted = true)
                        .filter { it.origin == CollectionOrigin.USER }
                        .sortedBy(CollectionList::id)
                        .forEach { list ->
                            records[listRecordId(list.id)] = list.toSyncRecord()
                        }
                }
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
            "Collections adapter cannot apply ${document.kind}"
        }

        val parsed = document.records.values
            .sortedBy(SyncRecordEnvelope::id)
            .map(::parseRecord)

        val activeCollections = parsed.filterIsInstance<ParsedCollectionRecord.Collection>()
            .filterNot(ParsedCollectionRecord.Collection::deleted)
        val activeFolders = parsed.filterIsInstance<ParsedCollectionRecord.Folder>()
            .filterNot(ParsedCollectionRecord.Folder::deleted)
        val activeLists = parsed.filterIsInstance<ParsedCollectionRecord.ListRecord>()
            .filterNot(ParsedCollectionRecord.ListRecord::deleted)

        activeCollections.forEach { applyActiveCollection(it) }

        val foldersById = activeFolders.associateBy { it.id }
        activeFolders
            .sortedWith(compareBy({ folderDepth(it, foldersById) }, { it.id }))
            .forEach { applyActiveFolder(it) }

        activeLists.forEach { applyActiveList(it) }

        parsed.filterIsInstance<ParsedCollectionRecord.ListRecord>()
            .filter(ParsedCollectionRecord.ListRecord::deleted)
            .forEach { applyDeletedList(it) }

        parsed.filterIsInstance<ParsedCollectionRecord.Folder>()
            .filter(ParsedCollectionRecord.Folder::deleted)
            .sortedByDescending { folderDepthForDeletion(it.id, parsed) }
            .forEach { applyDeletedFolder(it) }

        parsed.filterIsInstance<ParsedCollectionRecord.Collection>()
            .filter(ParsedCollectionRecord.Collection::deleted)
            .forEach { applyDeletedCollection(it) }
    }

    private suspend fun applyActiveCollection(record: ParsedCollectionRecord.Collection) {
        val existing = store.getCollection(record.id)
        store.upsertCollection(
            TsuzukiCollection(
                id = record.id,
                title = record.title,
                origin = CollectionOrigin.USER,
                sortOrder = record.sortOrder,
                schemaVersion = record.schemaVersion,
                revision = nextLocalRevision(existing?.revision),
                createdAt = existing?.createdAt ?: record.createdAt,
                updatedAt = record.updatedAt,
                deletedAt = null,
            ),
        )
    }

    private suspend fun applyActiveFolder(record: ParsedCollectionRecord.Folder) {
        val collection = requireNotNull(store.getCollection(record.collectionId)) {
            "Synced folder ${record.id} references missing Collection ${record.collectionId}"
        }
        require(collection.deletedAt == null) {
            "Synced folder ${record.id} references deleted Collection ${record.collectionId}"
        }

        val existing = store.getFolder(record.id)
        store.upsertFolder(
            CollectionFolder(
                id = record.id,
                collectionId = record.collectionId,
                parentFolderId = record.parentFolderId,
                title = record.title,
                origin = CollectionOrigin.USER,
                sortOrder = record.sortOrder,
                schemaVersion = record.schemaVersion,
                revision = nextLocalRevision(existing?.revision),
                createdAt = existing?.createdAt ?: record.createdAt,
                updatedAt = record.updatedAt,
                deletedAt = null,
            ),
        )
    }

    private suspend fun applyActiveList(record: ParsedCollectionRecord.ListRecord) {
        val existing = store.getList(record.id)
        store.upsertList(
            CollectionList(
                id = record.id,
                collectionId = record.collectionId,
                folderId = record.folderId,
                title = record.title,
                providerId = record.providerId,
                query = record.queryJson?.let(CollectionQueryJsonCodec::decode),
                sort = record.sort,
                layoutType = record.layoutType,
                sortOrder = record.sortOrder,
                enabled = record.enabled,
                origin = CollectionOrigin.USER,
                schemaVersion = record.schemaVersion,
                revision = nextLocalRevision(existing?.revision),
                createdAt = existing?.createdAt ?: record.createdAt,
                updatedAt = record.updatedAt,
                deletedAt = null,
            ),
        )
    }

    private suspend fun applyDeletedCollection(record: ParsedCollectionRecord.Collection) {
        val existing = store.getCollection(record.id) ?: return
        store.upsertCollection(
            existing.copy(
                revision = existing.revision + 1,
                updatedAt = maxOf(existing.updatedAt, record.updatedAt),
                deletedAt = record.deletedAt,
            ),
        )
    }

    private suspend fun applyDeletedFolder(record: ParsedCollectionRecord.Folder) {
        val existing = store.getFolder(record.id) ?: return
        store.upsertFolder(
            existing.copy(
                revision = existing.revision + 1,
                updatedAt = maxOf(existing.updatedAt, record.updatedAt),
                deletedAt = record.deletedAt,
            ),
        )
    }

    private suspend fun applyDeletedList(record: ParsedCollectionRecord.ListRecord) {
        val existing = store.getList(record.id) ?: return
        store.upsertList(
            existing.copy(
                revision = existing.revision + 1,
                updatedAt = maxOf(existing.updatedAt, record.updatedAt),
                deletedAt = record.deletedAt,
            ),
        )
    }

    private fun TsuzukiCollection.toSyncRecord() = SyncRecordEnvelope(
        id = collectionRecordId(id),
        revision = revisionSource.nextRevision(),
        updatedAtEpochMillis = updatedAt,
        deletedAtEpochMillis = deletedAt,
        fields = buildJsonObject {
            put("recordType", RECORD_TYPE_COLLECTION)
            put("title", title)
            put("sortOrder", sortOrder)
            put("schemaVersion", schemaVersion)
            put("createdAt", createdAt)
        },
    )

    private fun CollectionFolder.toSyncRecord() = SyncRecordEnvelope(
        id = folderRecordId(id),
        revision = revisionSource.nextRevision(),
        updatedAtEpochMillis = updatedAt,
        deletedAtEpochMillis = deletedAt,
        fields = buildJsonObject {
            put("recordType", RECORD_TYPE_FOLDER)
            put("collectionId", collectionId)
            put("parentFolderId", parentFolderId?.let(::JsonPrimitive) ?: JsonNull)
            put("title", title)
            put("sortOrder", sortOrder)
            put("schemaVersion", schemaVersion)
            put("createdAt", createdAt)
        },
    )

    private fun CollectionList.toSyncRecord() = SyncRecordEnvelope(
        id = listRecordId(id),
        revision = revisionSource.nextRevision(),
        updatedAtEpochMillis = updatedAt,
        deletedAtEpochMillis = deletedAt,
        fields = buildJsonObject {
            put("recordType", RECORD_TYPE_LIST)
            put("collectionId", collectionId)
            put("folderId", folderId)
            put("title", title)
            put("providerId", providerId)
            put(
                "queryJson",
                query?.let(CollectionQueryJsonCodec::encode)?.let(::JsonPrimitive) ?: JsonNull,
            )
            put("sort", sort.name)
            put("layoutType", layoutType?.let(::JsonPrimitive) ?: JsonNull)
            put("sortOrder", sortOrder)
            put("enabled", enabled)
            put("schemaVersion", schemaVersion)
            put("createdAt", createdAt)
        },
    )

    private fun parseRecord(record: SyncRecordEnvelope): ParsedCollectionRecord {
        val fields = record.fields
        return when (fields.requiredString("recordType")) {
            RECORD_TYPE_COLLECTION -> ParsedCollectionRecord.Collection(
                id = collectionIdFromRecord(record.id),
                deleted = record.isTombstone,
                deletedAt = record.deletedAtEpochMillis,
                title = fields.requiredString("title"),
                sortOrder = fields.requiredLong("sortOrder"),
                schemaVersion = fields.requiredLong("schemaVersion").toInt(),
                createdAt = fields.requiredLong("createdAt"),
                updatedAt = record.updatedAtEpochMillis,
            )

            RECORD_TYPE_FOLDER -> ParsedCollectionRecord.Folder(
                id = folderIdFromRecord(record.id),
                deleted = record.isTombstone,
                deletedAt = record.deletedAtEpochMillis,
                collectionId = fields.requiredString("collectionId"),
                parentFolderId = fields.optionalString("parentFolderId"),
                title = fields.requiredString("title"),
                sortOrder = fields.requiredLong("sortOrder"),
                schemaVersion = fields.requiredLong("schemaVersion").toInt(),
                createdAt = fields.requiredLong("createdAt"),
                updatedAt = record.updatedAtEpochMillis,
            )

            RECORD_TYPE_LIST -> ParsedCollectionRecord.ListRecord(
                id = listIdFromRecord(record.id),
                deleted = record.isTombstone,
                deletedAt = record.deletedAtEpochMillis,
                collectionId = fields.requiredString("collectionId"),
                folderId = fields.requiredString("folderId"),
                title = fields.requiredString("title"),
                providerId = fields.requiredString("providerId"),
                queryJson = fields.optionalString("queryJson"),
                sort = CatalogSort.valueOf(fields.requiredString("sort")),
                layoutType = fields.optionalString("layoutType"),
                sortOrder = fields.requiredLong("sortOrder"),
                enabled = fields.requiredBoolean("enabled"),
                schemaVersion = fields.requiredLong("schemaVersion").toInt(),
                createdAt = fields.requiredLong("createdAt"),
                updatedAt = record.updatedAtEpochMillis,
            )

            else -> error("Unknown Collections sync record type")
        }
    }

    private fun folderDepth(
        folder: ParsedCollectionRecord.Folder,
        byId: Map<String, ParsedCollectionRecord.Folder>,
        path: Set<String> = emptySet(),
    ): Int {
        require(folder.id !in path) {
            "Synced Collection folder hierarchy contains a cycle at ${folder.id}"
        }
        val parentId = folder.parentFolderId ?: return 0
        val parent = requireNotNull(byId[parentId]) {
            "Synced folder ${folder.id} references missing parent $parentId"
        }
        require(parent.collectionId == folder.collectionId) {
            "Synced folder ${folder.id} parent belongs to another Collection"
        }
        return 1 + folderDepth(parent, byId, path + folder.id)
    }

    private fun folderDepthForDeletion(
        folderId: String,
        records: List<ParsedCollectionRecord>,
    ): Int {
        val byId = records.filterIsInstance<ParsedCollectionRecord.Folder>().associateBy { it.id }
        var current = byId[folderId] ?: return 0
        val seen = mutableSetOf<String>()
        var depth = 0
        while (current.parentFolderId != null) {
            require(seen.add(current.id)) {
                "Synced Collection folder hierarchy contains a cycle at ${current.id}"
            }
            current = byId[current.parentFolderId] ?: break
            depth++
        }
        return depth
    }

    private fun nextLocalRevision(existing: Long?): Long = existing?.plus(1) ?: 0L

    private sealed interface ParsedCollectionRecord {
        val id: String
        val deleted: Boolean
        val deletedAt: Long?
        val updatedAt: Long

        data class Collection(
            override val id: String,
            override val deleted: Boolean,
            override val deletedAt: Long?,
            val title: String,
            val sortOrder: Long,
            val schemaVersion: Int,
            val createdAt: Long,
            override val updatedAt: Long,
        ) : ParsedCollectionRecord

        data class Folder(
            override val id: String,
            override val deleted: Boolean,
            override val deletedAt: Long?,
            val collectionId: String,
            val parentFolderId: String?,
            val title: String,
            val sortOrder: Long,
            val schemaVersion: Int,
            val createdAt: Long,
            override val updatedAt: Long,
        ) : ParsedCollectionRecord

        data class ListRecord(
            override val id: String,
            override val deleted: Boolean,
            override val deletedAt: Long?,
            val collectionId: String,
            val folderId: String,
            val title: String,
            val providerId: String,
            val queryJson: String?,
            val sort: CatalogSort,
            val layoutType: String?,
            val sortOrder: Long,
            val enabled: Boolean,
            val schemaVersion: Int,
            val createdAt: Long,
            override val updatedAt: Long,
        ) : ParsedCollectionRecord
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val RECORD_TYPE_COLLECTION = "collection"
        const val RECORD_TYPE_FOLDER = "folder"
        const val RECORD_TYPE_LIST = "list"
        const val COLLECTION_PREFIX = "collection:"
        const val FOLDER_PREFIX = "folder:"
        const val LIST_PREFIX = "list:"
    }
}

internal fun collectionRecordId(id: String): String = "collection:$id"

internal fun folderRecordId(id: String): String = "folder:$id"

internal fun listRecordId(id: String): String = "list:$id"

private fun collectionIdFromRecord(recordId: String): String =
    recordId.removeRequiredPrefix("collection:")

private fun folderIdFromRecord(recordId: String): String =
    recordId.removeRequiredPrefix("folder:")

private fun listIdFromRecord(recordId: String): String =
    recordId.removeRequiredPrefix("list:")

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
