package tachiyomi.data.tsuzuki.sync

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.collections.model.CollectionFolder
import tachiyomi.domain.tsuzuki.collections.model.CollectionList
import tachiyomi.domain.tsuzuki.collections.model.CollectionOrigin
import tachiyomi.domain.tsuzuki.collections.model.TsuzukiCollection
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue
import tachiyomi.domain.tsuzuki.collections.repository.CollectionStore
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision
import tachiyomi.domain.tsuzuki.sync.service.SyncClock
import tachiyomi.domain.tsuzuki.sync.service.SyncRevisionSource

class CollectionsSyncAdapterTest {

    @Test
    fun `round trip preserves user collection hierarchy and provider neutral query`() = runBlocking {
        val source = FakeCollectionStore()
        source.upsertCollection(collection("collection-1", "Favorites"))
        source.upsertFolder(folder("folder-1", "collection-1", null, "Reading"))
        source.upsertFolder(folder("folder-2", "collection-1", "folder-1", "Long"))
        source.upsertList(
            list(
                id = "list-1",
                collectionId = "collection-1",
                folderId = "folder-2",
                query = QueryExpression.Predicate(
                    field = QueryField.STATUS,
                    operator = QueryOperator.EQUALS,
                    value = QueryValue.of("current"),
                ),
            ),
        )
        source.upsertCollection(
            collection(
                id = "system:discover",
                title = "System",
                origin = CollectionOrigin.SYSTEM,
            ),
        )

        val exported = adapter(source).exportDocument()

        exported.kind shouldBe SyncDocumentKind.COLLECTIONS
        exported.records.keys.toList() shouldContainExactly listOf(
            collectionRecordId("collection-1"),
            folderRecordId("folder-1"),
            folderRecordId("folder-2"),
            listRecordId("list-1"),
        )

        val target = FakeCollectionStore()
        adapter(target).applyDocument(exported)

        target.getCollection("collection-1")!!.title shouldBe "Favorites"
        target.getFolder("folder-2")!!.parentFolderId shouldBe "folder-1"
        target.getList("list-1")!!.let { synced ->
            synced.providerId shouldBe "kitsu"
            synced.sort shouldBe CatalogSort.POPULARITY_DESC
            synced.query shouldBe QueryExpression.Predicate(
                field = QueryField.STATUS,
                operator = QueryOperator.EQUALS,
                value = QueryValue.of("current"),
            )
        }
        target.getCollection("system:discover") shouldBe null
    }

    @Test
    fun `tombstones soft delete existing entities without deleting their rows`() = runBlocking {
        val source = FakeCollectionStore()
        source.upsertCollection(collection("collection-1", "Favorites", deletedAt = 500L))
        source.upsertFolder(folder("folder-1", "collection-1", null, "Reading", deletedAt = 500L))
        source.upsertList(
            list(
                id = "list-1",
                collectionId = "collection-1",
                folderId = "folder-1",
                query = null,
                deletedAt = 500L,
            ),
        )
        val exported = adapter(source).exportDocument()

        val target = FakeCollectionStore()
        target.upsertCollection(collection("collection-1", "Old"))
        target.upsertFolder(folder("folder-1", "collection-1", null, "Old folder"))
        target.upsertList(
            list(
                id = "list-1",
                collectionId = "collection-1",
                folderId = "folder-1",
                query = null,
            ),
        )

        adapter(target).applyDocument(exported)

        target.getList("list-1")!!.deletedAt shouldBe 500L
        target.getFolder("folder-1")!!.deletedAt shouldBe 500L
        target.getCollection("collection-1")!!.deletedAt shouldBe 500L
    }

    private fun adapter(store: CollectionStore) = CollectionsSyncAdapter(
        store = store,
        revisionSource = object : SyncRevisionSource {
            override val deviceId = "test-device"
            private var sequence = 0L
            override fun nextRevision() = SyncRevision(deviceId, ++sequence)
        },
        clock = object : SyncClock {
            override fun nowEpochMillis(): Long = 1_000L
        },
    )

    private fun collection(
        id: String,
        title: String,
        origin: CollectionOrigin = CollectionOrigin.USER,
        deletedAt: Long? = null,
    ) = TsuzukiCollection(
        id = id,
        title = title,
        origin = origin,
        sortOrder = 0L,
        revision = 1L,
        createdAt = 10L,
        updatedAt = deletedAt ?: 20L,
        deletedAt = deletedAt,
    )

    private fun folder(
        id: String,
        collectionId: String,
        parentFolderId: String?,
        title: String,
        deletedAt: Long? = null,
    ) = CollectionFolder(
        id = id,
        collectionId = collectionId,
        parentFolderId = parentFolderId,
        title = title,
        origin = CollectionOrigin.USER,
        sortOrder = 0L,
        revision = 1L,
        createdAt = 10L,
        updatedAt = deletedAt ?: 20L,
        deletedAt = deletedAt,
    )

    private fun list(
        id: String,
        collectionId: String,
        folderId: String,
        query: QueryExpression?,
        deletedAt: Long? = null,
    ) = CollectionList(
        id = id,
        collectionId = collectionId,
        folderId = folderId,
        title = "Current",
        providerId = "kitsu",
        query = query,
        sort = CatalogSort.POPULARITY_DESC,
        layoutType = "grid",
        sortOrder = 0L,
        enabled = true,
        origin = CollectionOrigin.USER,
        revision = 1L,
        createdAt = 10L,
        updatedAt = deletedAt ?: 20L,
        deletedAt = deletedAt,
    )

    private class FakeCollectionStore : CollectionStore {
        private val collections = linkedMapOf<String, TsuzukiCollection>()
        private val folders = linkedMapOf<String, CollectionFolder>()
        private val lists = linkedMapOf<String, CollectionList>()

        override suspend fun getCollection(id: String): TsuzukiCollection? = collections[id]

        override suspend fun getCollections(includeDeleted: Boolean): List<TsuzukiCollection> =
            collections.values.filter { includeDeleted || it.deletedAt == null }

        override fun observeCollections(): Flow<List<TsuzukiCollection>> =
            flowOf(collections.values.filter { it.deletedAt == null })

        override suspend fun upsertCollection(collection: TsuzukiCollection) {
            collections[collection.id] = collection
        }

        override suspend fun getFolder(id: String): CollectionFolder? = folders[id]

        override suspend fun getFolders(
            collectionId: String,
            includeDeleted: Boolean,
        ): List<CollectionFolder> = folders.values.filter {
            it.collectionId == collectionId && (includeDeleted || it.deletedAt == null)
        }

        override fun observeFolders(collectionId: String): Flow<List<CollectionFolder>> =
            flowOf(folders.values.filter { it.collectionId == collectionId && it.deletedAt == null })

        override suspend fun upsertFolder(folder: CollectionFolder) {
            folders[folder.id] = folder
        }

        override suspend fun getList(id: String): CollectionList? = lists[id]

        override suspend fun getLists(
            folderId: String,
            includeDeleted: Boolean,
        ): List<CollectionList> = lists.values.filter {
            it.folderId == folderId && (includeDeleted || it.deletedAt == null)
        }

        override fun observeLists(folderId: String): Flow<List<CollectionList>> =
            flowOf(lists.values.filter { it.folderId == folderId && it.deletedAt == null })

        override suspend fun upsertList(list: CollectionList) {
            lists[list.id] = list
        }
    }
}
