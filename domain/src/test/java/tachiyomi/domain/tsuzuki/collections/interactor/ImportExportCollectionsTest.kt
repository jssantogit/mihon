package tachiyomi.domain.tsuzuki.collections.interactor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.collections.model.CollectionFolder
import tachiyomi.domain.tsuzuki.collections.model.CollectionList
import tachiyomi.domain.tsuzuki.collections.model.CollectionOrigin
import tachiyomi.domain.tsuzuki.collections.model.TsuzukiCollection
import tachiyomi.domain.tsuzuki.collections.portable.CollectionPortableCodec
import tachiyomi.domain.tsuzuki.collections.portable.PortableCollectionsDocument
import tachiyomi.domain.tsuzuki.collections.repository.CollectionStore

class ImportExportCollectionsTest {

    @Test
    fun `export includes active user graph and excludes system definitions by default`() = runTest {
        val store = FakeStore()
        val codec = FakeCodec()

        val user = collection("user", CollectionOrigin.USER)
        val system = collection("system", CollectionOrigin.SYSTEM)
        val userFolder = folder("user-folder", user.id, CollectionOrigin.USER)
        val systemFolder = folder("system-folder", system.id, CollectionOrigin.SYSTEM)
        val userList = list("user-list", user.id, userFolder.id, CollectionOrigin.USER)
        val systemList = list("system-list", system.id, systemFolder.id, CollectionOrigin.SYSTEM)

        store.upsertCollection(user)
        store.upsertCollection(system)
        store.upsertFolder(userFolder)
        store.upsertFolder(systemFolder)
        store.upsertList(userList)
        store.upsertList(systemList)

        ExportCollections(store, codec).execute() shouldBe "encoded"

        val exported = codec.lastEncoded!!
        exported.collections.map { it.id } shouldContainExactly listOf("user")
        exported.folders.map { it.id } shouldContainExactly listOf("user-folder")
        exported.lists.map { it.id } shouldContainExactly listOf("user-list")
    }

    @Test
    fun `export can include system definitions explicitly`() = runTest {
        val store = FakeStore()
        val codec = FakeCodec()

        store.upsertCollection(collection("user", CollectionOrigin.USER))
        store.upsertCollection(collection("system", CollectionOrigin.SYSTEM))

        ExportCollections(store, codec).execute(includeSystem = true)

        codec.lastEncoded!!.collections.map { it.id }.toSet() shouldBe setOf("user", "system")
    }

    @Test
    fun `import preserves available ids but always creates user owned active definitions`() = runTest {
        val document = graphDocument(
            collectionId = "collection",
            rootFolderId = "root",
            childFolderId = "child",
            listId = "list",
            origin = CollectionOrigin.USER,
        )
        val store = FakeStore()
        val codec = FakeCodec(document)

        val importer = ImportCollections(
            store = store,
            codec = codec,
            idFactory = { error("idFactory should not be needed") },
            clock = { 5_000 },
        )

        val result = importer.execute("ignored")

        result.collectionIds shouldContainExactly listOf("collection")
        result.folderCount shouldBe 2
        result.listCount shouldBe 1
        result.remappedIdCount shouldBe 0

        val importedCollection = store.getCollection("collection")!!
        importedCollection.origin shouldBe CollectionOrigin.USER
        importedCollection.revision shouldBe 0
        importedCollection.createdAt shouldBe 5_000
        importedCollection.updatedAt shouldBe 5_000
        importedCollection.deletedAt shouldBe null

        val importedChild = store.getFolder("child")!!
        importedChild.parentFolderId shouldBe "root"
        importedChild.origin shouldBe CollectionOrigin.USER

        val importedList = store.getList("list")!!
        importedList.collectionId shouldBe "collection"
        importedList.folderId shouldBe "child"
        importedList.origin shouldBe CollectionOrigin.USER
    }

    @Test
    fun `system owned portable ids are always remapped into user namespace`() = runTest {
        val document = graphDocument(
            collectionId = "system:discover",
            rootFolderId = "system:discover:kitsu",
            childFolderId = "system:discover:kitsu:child",
            listId = "system:discover:kitsu:popular",
            origin = CollectionOrigin.SYSTEM,
        )
        val ids = ArrayDeque(
            listOf(
                "imported-collection",
                "imported-root",
                "imported-child",
                "imported-list",
            ),
        )
        val store = FakeStore()

        val result = ImportCollections(
            store = store,
            codec = FakeCodec(document),
            idFactory = { ids.removeFirst() },
            clock = { 7_000 },
        ).execute("ignored")

        result.collectionIds shouldContainExactly listOf("imported-collection")
        result.remappedIdCount shouldBe 4

        val folders = store.getFolders("imported-collection")
        folders.map { it.id }.toSet() shouldBe setOf("imported-root", "imported-child")
        folders.first { it.id == "imported-child" }.parentFolderId shouldBe "imported-root"

        val importedList = store.getList("imported-list")!!
        importedList.collectionId shouldBe "imported-collection"
        importedList.folderId shouldBe "imported-child"
        importedList.origin shouldBe CollectionOrigin.USER

        store.getCollection("system:discover") shouldBe null
        store.getFolder("system:discover:kitsu") shouldBe null
        store.getList("system:discover:kitsu:popular") shouldBe null
    }

    @Test
    fun `colliding ids are remapped throughout nested graph without overwriting existing data`() = runTest {
        val document = graphDocument(
            collectionId = "collection",
            rootFolderId = "root",
            childFolderId = "child",
            listId = "list",
        )
        val store = FakeStore()
        store.upsertCollection(collection("collection", CollectionOrigin.USER))
        store.upsertFolder(folder("root", "collection", CollectionOrigin.USER))
        store.upsertList(list("list", "collection", "root", CollectionOrigin.USER))

        val ids = ArrayDeque(listOf("collection-copy", "root-copy", "list-copy"))
        val importer = ImportCollections(
            store = store,
            codec = FakeCodec(document),
            idFactory = { ids.removeFirst() },
            clock = { 9_000 },
        )

        val result = importer.execute("ignored")

        result.collectionIds shouldContainExactly listOf("collection-copy")
        result.remappedIdCount shouldBe 3

        val importedFolders = store.getFolders("collection-copy")
        importedFolders.map { it.id }.toSet() shouldBe setOf("root-copy", "child")
        importedFolders.first { it.id == "child" }.parentFolderId shouldBe "root-copy"

        val importedList = store.getList("list-copy")!!
        importedList.collectionId shouldBe "collection-copy"
        importedList.folderId shouldBe "child"

        store.getCollection("collection")!!.title shouldBe "Collection collection"
        store.getFolder("root")!!.collectionId shouldBe "collection"
        store.getList("list")!!.folderId shouldBe "root"
    }

    @Test
    fun `malformed references fail before any write occurs`() = runTest {
        val base = graphDocument(
            collectionId = "collection",
            rootFolderId = "root",
            childFolderId = "child",
            listId = "list",
        )
        val malformed = base.copy(
            lists = listOf(
                base.lists.single().copy(folderId = "missing"),
            ),
        )
        val store = FakeStore()
        val importer = ImportCollections(
            store = store,
            codec = FakeCodec(malformed),
            idFactory = { "unused" },
            clock = { 1_000 },
        )

        shouldThrow<IllegalArgumentException> {
            importer.execute("ignored")
        }

        store.writeCount shouldBe 0
    }

    @Test
    fun `folder cycles fail before any write occurs`() = runTest {
        val collection = collection("collection", CollectionOrigin.USER)
        val first = folder(
            id = "first",
            collectionId = collection.id,
            origin = CollectionOrigin.USER,
            parentFolderId = "second",
        )
        val second = folder(
            id = "second",
            collectionId = collection.id,
            origin = CollectionOrigin.USER,
            parentFolderId = "first",
        )
        val document = PortableCollectionsDocument(
            collections = listOf(collection),
            folders = listOf(first, second),
            lists = emptyList(),
        )
        val store = FakeStore()
        val importer = ImportCollections(
            store = store,
            codec = FakeCodec(document),
            idFactory = { "unused" },
            clock = { 1_000 },
        )

        shouldThrow<IllegalArgumentException> {
            importer.execute("ignored")
        }

        store.writeCount shouldBe 0
    }

    @Test
    fun `tombstoned portable definitions are rejected rather than resurrected silently`() = runTest {
        val document = PortableCollectionsDocument(
            collections = listOf(
                collection("collection", CollectionOrigin.USER).copy(deletedAt = 100),
            ),
            folders = emptyList(),
            lists = emptyList(),
        )
        val store = FakeStore()

        shouldThrow<IllegalArgumentException> {
            ImportCollections(
                store = store,
                codec = FakeCodec(document),
                idFactory = { "unused" },
                clock = { 1_000 },
            ).execute("ignored")
        }

        store.writeCount shouldBe 0
    }

    private fun graphDocument(
        collectionId: String,
        rootFolderId: String,
        childFolderId: String,
        listId: String,
        origin: CollectionOrigin = CollectionOrigin.USER,
    ): PortableCollectionsDocument {
        val collection = collection(collectionId, origin)
        val root = folder(rootFolderId, collectionId, origin)
        val child = folder(
            id = childFolderId,
            collectionId = collectionId,
            origin = origin,
            parentFolderId = rootFolderId,
        )
        return PortableCollectionsDocument(
            collections = listOf(collection),
            folders = listOf(child, root),
            lists = listOf(
                list(
                    id = listId,
                    collectionId = collectionId,
                    folderId = childFolderId,
                    origin = origin,
                ),
            ),
        )
    }

    private fun collection(
        id: String,
        origin: CollectionOrigin,
    ) = TsuzukiCollection(
        id = id,
        title = "Collection $id",
        origin = origin,
        sortOrder = 0,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun folder(
        id: String,
        collectionId: String,
        origin: CollectionOrigin,
        parentFolderId: String? = null,
    ) = CollectionFolder(
        id = id,
        collectionId = collectionId,
        parentFolderId = parentFolderId,
        title = "Folder $id",
        origin = origin,
        sortOrder = 0,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun list(
        id: String,
        collectionId: String,
        folderId: String,
        origin: CollectionOrigin,
    ) = CollectionList(
        id = id,
        collectionId = collectionId,
        folderId = folderId,
        title = "List $id",
        providerId = "kitsu",
        query = null,
        sort = CatalogSort.POPULARITY_DESC,
        sortOrder = 0,
        origin = origin,
        createdAt = 1,
        updatedAt = 1,
    )

    private class FakeCodec(
        private val decodeResult: PortableCollectionsDocument? = null,
    ) : CollectionPortableCodec {
        var lastEncoded: PortableCollectionsDocument? = null

        override fun encode(document: PortableCollectionsDocument): String {
            lastEncoded = document
            return "encoded"
        }

        override fun decode(encoded: String): PortableCollectionsDocument {
            return requireNotNull(decodeResult)
        }
    }

    private class FakeStore : CollectionStore {
        private val collections = linkedMapOf<String, TsuzukiCollection>()
        private val folders = linkedMapOf<String, CollectionFolder>()
        private val lists = linkedMapOf<String, CollectionList>()

        var writeCount: Int = 0
            private set

        override suspend fun getCollection(id: String): TsuzukiCollection? = collections[id]

        override suspend fun getCollections(includeDeleted: Boolean): List<TsuzukiCollection> {
            return collections.values
                .filter { includeDeleted || it.deletedAt == null }
                .sortedBy { it.sortOrder }
        }

        override fun observeCollections(): Flow<List<TsuzukiCollection>> {
            return flowOf(collections.values.filter { it.deletedAt == null })
        }

        override suspend fun upsertCollection(collection: TsuzukiCollection) {
            writeCount++
            collections[collection.id] = collection
        }

        override suspend fun getFolder(id: String): CollectionFolder? = folders[id]

        override suspend fun getFolders(
            collectionId: String,
            includeDeleted: Boolean,
        ): List<CollectionFolder> {
            return folders.values
                .filter { it.collectionId == collectionId }
                .filter { includeDeleted || it.deletedAt == null }
                .sortedBy { it.sortOrder }
        }

        override fun observeFolders(collectionId: String): Flow<List<CollectionFolder>> {
            return flowOf(
                folders.values.filter {
                    it.collectionId == collectionId && it.deletedAt == null
                },
            )
        }

        override suspend fun upsertFolder(folder: CollectionFolder) {
            writeCount++
            folders[folder.id] = folder
        }

        override suspend fun getList(id: String): CollectionList? = lists[id]

        override suspend fun getLists(
            folderId: String,
            includeDeleted: Boolean,
        ): List<CollectionList> {
            return lists.values
                .filter { it.folderId == folderId }
                .filter { includeDeleted || it.deletedAt == null }
                .sortedBy { it.sortOrder }
        }

        override fun observeLists(folderId: String): Flow<List<CollectionList>> {
            return flowOf(
                lists.values.filter {
                    it.folderId == folderId && it.deletedAt == null
                },
            )
        }

        override suspend fun upsertList(list: CollectionList) {
            writeCount++
            lists[list.id] = list
        }
    }
}
