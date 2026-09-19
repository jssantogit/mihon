package tachiyomi.domain.tsuzuki.collections.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.collections.model.CURRENT_COLLECTION_SCHEMA_VERSION
import tachiyomi.domain.tsuzuki.collections.model.CollectionFolder
import tachiyomi.domain.tsuzuki.collections.model.CollectionOrigin
import tachiyomi.domain.tsuzuki.collections.portable.CollectionPortableCodec
import tachiyomi.domain.tsuzuki.collections.portable.PortableCollectionsDocument
import tachiyomi.domain.tsuzuki.collections.repository.CollectionStore
import java.util.UUID
import kotlin.time.Clock

data class ImportCollectionsResult(
    val collectionIds: List<String>,
    val folderCount: Int,
    val listCount: Int,
    val remappedIdCount: Int,
)

class ImportCollections internal constructor(
    private val store: CollectionStore,
    private val codec: CollectionPortableCodec,
    private val idFactory: () -> String,
    private val clock: () -> Long,
) {

    @Inject
    constructor(
        store: CollectionStore,
        codec: CollectionPortableCodec,
    ) : this(
        store = store,
        codec = codec,
        idFactory = { UUID.randomUUID().toString() },
        clock = { Clock.System.now().toEpochMilliseconds() },
    )

    suspend fun execute(encoded: String): ImportCollectionsResult {
        val document = codec.decode(encoded)
        validate(document)

        val now = clock()
        val collectionIdMap = mutableMapOf<String, String>()
        val folderIdMap = mutableMapOf<String, String>()
        val listIdMap = mutableMapOf<String, String>()
        var remapped = 0

        for (collection in document.collections) {
            val targetId = allocateId(
                preferred = collection.id,
                forceRemap = collection.origin == CollectionOrigin.SYSTEM || collection.id.startsWith(SYSTEM_ID_PREFIX),
                reserved = collectionIdMap.values.toSet(),
                exists = { store.getCollection(it) != null },
            )
            if (targetId != collection.id) remapped++
            collectionIdMap[collection.id] = targetId
        }

        for (folder in document.folders) {
            val targetId = allocateId(
                preferred = folder.id,
                forceRemap = folder.origin == CollectionOrigin.SYSTEM || folder.id.startsWith(SYSTEM_ID_PREFIX),
                reserved = folderIdMap.values.toSet(),
                exists = { store.getFolder(it) != null },
            )
            if (targetId != folder.id) remapped++
            folderIdMap[folder.id] = targetId
        }

        for (list in document.lists) {
            val targetId = allocateId(
                preferred = list.id,
                forceRemap = list.origin == CollectionOrigin.SYSTEM || list.id.startsWith(SYSTEM_ID_PREFIX),
                reserved = listIdMap.values.toSet(),
                exists = { store.getList(it) != null },
            )
            if (targetId != list.id) remapped++
            listIdMap[list.id] = targetId
        }

        for (collection in document.collections) {
            store.upsertCollection(
                collection.copy(
                    id = collectionIdMap.getValue(collection.id),
                    origin = CollectionOrigin.USER,
                    schemaVersion = CURRENT_COLLECTION_SCHEMA_VERSION,
                    revision = 0,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                ),
            )
        }

        val sourceFoldersById = document.folders.associateBy(CollectionFolder::id)
        for (folder in document.folders.sortedBy { folderDepth(it, sourceFoldersById) }) {
            store.upsertFolder(
                folder.copy(
                    id = folderIdMap.getValue(folder.id),
                    collectionId = collectionIdMap.getValue(folder.collectionId),
                    parentFolderId = folder.parentFolderId?.let { folderIdMap.getValue(it) },
                    origin = CollectionOrigin.USER,
                    schemaVersion = CURRENT_COLLECTION_SCHEMA_VERSION,
                    revision = 0,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                ),
            )
        }

        for (list in document.lists) {
            store.upsertList(
                list.copy(
                    id = listIdMap.getValue(list.id),
                    collectionId = collectionIdMap.getValue(list.collectionId),
                    folderId = folderIdMap.getValue(list.folderId),
                    origin = CollectionOrigin.USER,
                    schemaVersion = CURRENT_COLLECTION_SCHEMA_VERSION,
                    revision = 0,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                ),
            )
        }

        return ImportCollectionsResult(
            collectionIds = document.collections.map { collectionIdMap.getValue(it.id) },
            folderCount = document.folders.size,
            listCount = document.lists.size,
            remappedIdCount = remapped,
        )
    }

    suspend operator fun invoke(encoded: String): ImportCollectionsResult = execute(encoded)

    private suspend fun allocateId(
        preferred: String,
        forceRemap: Boolean,
        reserved: Set<String>,
        exists: suspend (String) -> Boolean,
    ): String {
        if (!forceRemap && preferred !in reserved && !exists(preferred)) return preferred

        repeat(MAX_ID_ATTEMPTS) {
            val candidate = idFactory()
            if (candidate !in reserved && !exists(candidate)) {
                return candidate
            }
        }

        error("Unable to allocate unique Collection import id after $MAX_ID_ATTEMPTS attempts")
    }

    private fun validate(document: PortableCollectionsDocument) {
        requireUniqueIds("Collection", document.collections.map { it.id })
        requireUniqueIds("Folder", document.folders.map { it.id })
        requireUniqueIds("List", document.lists.map { it.id })

        require(document.collections.none { it.deletedAt != null }) {
            "Portable Collection imports cannot contain tombstoned Collections"
        }
        require(document.folders.none { it.deletedAt != null }) {
            "Portable Collection imports cannot contain tombstoned folders"
        }
        require(document.lists.none { it.deletedAt != null }) {
            "Portable Collection imports cannot contain tombstoned Lists"
        }

        val collectionsById = document.collections.associateBy { it.id }
        val foldersById = document.folders.associateBy { it.id }

        for (folder in document.folders) {
            require(folder.collectionId in collectionsById) {
                "Folder ${folder.id} references missing Collection ${folder.collectionId}"
            }

            folder.parentFolderId?.let { parentId ->
                val parent = requireNotNull(foldersById[parentId]) {
                    "Folder ${folder.id} references missing parent $parentId"
                }
                require(parent.collectionId == folder.collectionId) {
                    "Folder ${folder.id} parent belongs to another Collection"
                }
            }

            folderDepth(folder, foldersById)
        }

        for (list in document.lists) {
            require(list.collectionId in collectionsById) {
                "List ${list.id} references missing Collection ${list.collectionId}"
            }
            val folder = requireNotNull(foldersById[list.folderId]) {
                "List ${list.id} references missing folder ${list.folderId}"
            }
            require(folder.collectionId == list.collectionId) {
                "List ${list.id} folder belongs to another Collection"
            }
        }
    }

    private fun requireUniqueIds(
        label: String,
        ids: List<String>,
    ) {
        require(ids.size == ids.toSet().size) {
            "Portable Collections document contains duplicate $label ids"
        }
    }

    private fun folderDepth(
        folder: CollectionFolder,
        byId: Map<String, CollectionFolder>,
        path: Set<String> = emptySet(),
    ): Int {
        require(folder.id !in path) {
            "Portable Collections folder hierarchy contains a cycle at ${folder.id}"
        }
        val parentId = folder.parentFolderId ?: return 0
        val parent = requireNotNull(byId[parentId]) {
            "Folder ${folder.id} references missing parent $parentId"
        }
        return 1 + folderDepth(parent, byId, path + folder.id)
    }

    private companion object {
        const val MAX_ID_ATTEMPTS = 100
        const val SYSTEM_ID_PREFIX = "system:"
    }
}
