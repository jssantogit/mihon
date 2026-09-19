package tachiyomi.domain.tsuzuki.collections.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

class CollectionModelsTest {

    @Test
    fun `folder preserves nested parent relationship`() {
        val folder = CollectionFolder(
            id = "child",
            collectionId = "collection",
            parentFolderId = "parent",
            title = "Child",
            origin = CollectionOrigin.USER,
            sortOrder = 1,
            createdAt = 10,
            updatedAt = 20,
        )

        folder.parentFolderId shouldBe "parent"
        folder.collectionId shouldBe "collection"
        folder.schemaVersion shouldBe CURRENT_COLLECTION_SCHEMA_VERSION
    }

    @Test
    fun `folder rejects self parent relationship`() {
        shouldThrow<IllegalArgumentException> {
            CollectionFolder(
                id = "same",
                collectionId = "collection",
                parentFolderId = "same",
                title = "Invalid",
                origin = CollectionOrigin.USER,
                sortOrder = 0,
                createdAt = 10,
                updatedAt = 10,
            )
        }
    }

    @Test
    fun `list retains provider neutral query and portable metadata`() {
        val query = QueryExpression.Predicate(
            QueryField.STATUS,
            QueryOperator.EQUALS,
            QueryValue.of("completed"),
        )
        val list = CollectionList(
            id = "list",
            collectionId = "collection",
            folderId = "folder",
            title = "Completed",
            providerId = "kitsu",
            query = query,
            sort = CatalogSort.RATING_DESC,
            layoutType = "grid",
            sortOrder = 2,
            enabled = true,
            origin = CollectionOrigin.SYSTEM,
            revision = 4,
            createdAt = 10,
            updatedAt = 20,
            deletedAt = 30,
        )

        list.query shouldBe query
        list.origin shouldBe CollectionOrigin.SYSTEM
        list.revision shouldBe 4
        list.deletedAt shouldBe 30
    }
}
