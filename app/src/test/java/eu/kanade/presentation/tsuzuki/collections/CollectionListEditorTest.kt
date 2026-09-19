package eu.kanade.presentation.tsuzuki.collections

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.collections.model.CollectionList
import tachiyomi.domain.tsuzuki.collections.model.CollectionOrigin
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

class CollectionListEditorTest {

    @Test
    fun `simple supported query remains editable and round trips semantically`() {
        val query = QueryExpression.All(
            QueryExpression.Predicate(
                QueryField.STATUS,
                QueryOperator.EQUALS,
                QueryValue.of("ONGOING"),
            ),
            QueryExpression.Predicate(
                QueryField.SCORE,
                QueryOperator.GREATER_OR_EQUAL,
                QueryValue.of(80.0),
            ),
            QueryExpression.Predicate(
                QueryField.CHAPTER_COUNT,
                QueryOperator.GREATER_OR_EQUAL,
                QueryValue.of(20),
            ),
        )
        val list = list(query)

        val editor = ListEditorState.from(list)

        editor.filtersEditable shouldBe true
        editor.status shouldBe "ONGOING"
        editor.minScore shouldBe "80.0"
        editor.minChapters shouldBe "20"

        editor.toDraftOrNull()!!.query!!.normalize() shouldBe query.normalize()
    }

    @Test
    fun `advanced any query is preserved exactly when visual editor cannot represent it`() {
        val query = QueryExpression.Any(
            QueryExpression.Predicate(
                QueryField.STATUS,
                QueryOperator.EQUALS,
                QueryValue.of("ONGOING"),
            ),
            QueryExpression.Not(
                QueryExpression.Predicate(
                    QueryField.WORK_TYPE,
                    QueryOperator.EQUALS,
                    QueryValue.of("NOVEL"),
                ),
            ),
        )
        val list = list(query)

        val editor = ListEditorState.from(list)

        editor.filtersEditable shouldBe false
        editor.preservedQuery shouldBe query
        editor.copy(
            title = "Renamed",
            sort = CatalogSort.RATING_DESC,
        ).toDraftOrNull()!!.let { draft ->
            draft.title shouldBe "Renamed"
            draft.sort shouldBe CatalogSort.RATING_DESC
            draft.query shouldBe query
        }
    }

    @Test
    fun `unsupported simple field is preserved instead of dropped`() {
        val query = QueryExpression.Predicate(
            QueryField.GENRE,
            QueryOperator.EQUALS,
            QueryValue.of("Action"),
        )

        val editor = ListEditorState.from(list(query))

        editor.filtersEditable shouldBe false
        editor.toDraftOrNull()!!.query shouldBe query
    }

    private fun list(query: QueryExpression): CollectionList = CollectionList(
        id = "list",
        collectionId = "collection",
        folderId = "folder",
        title = "List",
        providerId = "kitsu",
        query = query,
        sort = CatalogSort.POPULARITY_DESC,
        sortOrder = 0,
        origin = CollectionOrigin.USER,
        createdAt = 1,
        updatedAt = 1,
    )
}
