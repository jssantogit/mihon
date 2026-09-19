package tachiyomi.data.tsuzuki.collections

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.collections.model.CollectionFolder
import tachiyomi.domain.tsuzuki.collections.model.CollectionList
import tachiyomi.domain.tsuzuki.collections.model.CollectionOrigin
import tachiyomi.domain.tsuzuki.collections.model.TsuzukiCollection
import tachiyomi.domain.tsuzuki.collections.portable.PortableCollectionsDocument
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

class CollectionPortableJsonCodecTest {

    private val codec = CollectionPortableJsonCodec()

    @Test
    fun `portable json v1 round trips collection folder list and query graph`() {
        val collection = TsuzukiCollection(
            id = "collection",
            title = "My Collection",
            origin = CollectionOrigin.USER,
            sortOrder = 2,
            revision = 3,
            createdAt = 10,
            updatedAt = 20,
        )
        val root = CollectionFolder(
            id = "root",
            collectionId = collection.id,
            title = "Root",
            origin = CollectionOrigin.USER,
            sortOrder = 0,
            createdAt = 10,
            updatedAt = 20,
        )
        val child = CollectionFolder(
            id = "child",
            collectionId = collection.id,
            parentFolderId = root.id,
            title = "Child",
            origin = CollectionOrigin.USER,
            sortOrder = 1,
            createdAt = 10,
            updatedAt = 20,
        )
        val list = CollectionList(
            id = "list",
            collectionId = collection.id,
            folderId = child.id,
            title = "Completed action",
            providerId = "kitsu",
            query = QueryExpression.All(
                QueryExpression.Predicate(
                    QueryField.STATUS,
                    QueryOperator.EQUALS,
                    QueryValue.of("completed"),
                ),
                QueryExpression.Predicate(
                    QueryField.GENRE,
                    QueryOperator.IN,
                    QueryValue.of(
                        listOf(
                            QueryValue.of("Action"),
                            QueryValue.of("Drama"),
                        ),
                    ),
                ),
            ),
            sort = CatalogSort.RATING_DESC,
            layoutType = "grid",
            sortOrder = 4,
            enabled = false,
            origin = CollectionOrigin.USER,
            revision = 7,
            createdAt = 10,
            updatedAt = 20,
        )

        val document = PortableCollectionsDocument(
            collections = listOf(collection),
            folders = listOf(child, root),
            lists = listOf(list),
        )

        codec.decode(codec.encode(document)) shouldBe document.copy(
            folders = listOf(root, child),
        )
    }

    @Test
    fun `encoding is deterministic regardless of input order`() {
        val first = collection("a", sortOrder = 1)
        val second = collection("b", sortOrder = 0)
        val firstFolder = folder("a-folder", first.id, sortOrder = 1)
        val secondFolder = folder("b-folder", second.id, sortOrder = 0)
        val firstList = list("a-list", first.id, firstFolder.id, sortOrder = 1)
        val secondList = list("b-list", second.id, secondFolder.id, sortOrder = 0)

        val a = PortableCollectionsDocument(
            collections = listOf(first, second),
            folders = listOf(firstFolder, secondFolder),
            lists = listOf(firstList, secondList),
        )
        val b = PortableCollectionsDocument(
            collections = listOf(second, first),
            folders = listOf(secondFolder, firstFolder),
            lists = listOf(secondList, firstList),
        )

        codec.encode(a) shouldBe codec.encode(b)
    }

    @Test
    fun `unsupported document schema version fails closed`() {
        val encoded = codec.encode(
            PortableCollectionsDocument(
                collections = emptyList(),
                folders = emptyList(),
                lists = emptyList(),
            ),
        ).replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")

        shouldThrow<IllegalArgumentException> {
            codec.decode(encoded)
        }
    }

    private fun collection(
        id: String,
        sortOrder: Long = 0,
    ) = TsuzukiCollection(
        id = id,
        title = "Collection $id",
        origin = CollectionOrigin.USER,
        sortOrder = sortOrder,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun folder(
        id: String,
        collectionId: String,
        sortOrder: Long = 0,
    ) = CollectionFolder(
        id = id,
        collectionId = collectionId,
        title = "Folder $id",
        origin = CollectionOrigin.USER,
        sortOrder = sortOrder,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun list(
        id: String,
        collectionId: String,
        folderId: String,
        sortOrder: Long = 0,
        query: QueryExpression? = null,
    ) = CollectionList(
        id = id,
        collectionId = collectionId,
        folderId = folderId,
        title = "List $id",
        providerId = "kitsu",
        query = query,
        sort = CatalogSort.POPULARITY_DESC,
        sortOrder = sortOrder,
        origin = CollectionOrigin.USER,
        createdAt = 1,
        updatedAt = 1,
    )
}
