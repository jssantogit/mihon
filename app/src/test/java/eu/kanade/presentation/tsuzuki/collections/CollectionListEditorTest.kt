package eu.kanade.presentation.tsuzuki.collections

import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionListDraft
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.collections.model.CollectionList
import tachiyomi.domain.tsuzuki.collections.model.CollectionOrigin
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortDirection
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortKey
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortSelection
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
    fun `draft list opens in the same visual editor without losing its query`() {
        val query = QueryExpression.Predicate(
            QueryField.STATUS,
            QueryOperator.EQUALS,
            QueryValue.of("ONGOING"),
        )
        val draft = CollectionListDraft(
            title = "Draft",
            query = query,
            sort = CollectionSortSelection(
            CollectionSortKey.Standard.RATING,
            CollectionSortDirection.DESC,
        ),
            layoutType = "list",
        )

        val editor = ListEditorState.fromDraft(draft)

        editor.title shouldBe "Draft"
        editor.sort shouldBe CollectionSortSelection(
            CollectionSortKey.Standard.RATING,
            CollectionSortDirection.DESC,
        )
        editor.layoutType shouldBe "list"
        editor.status shouldBe "ONGOING"
        editor.toDraftOrNull()!!.query!!.normalize() shouldBe query.normalize()
    }

    @Test
    fun `draft provider selection survives editor round trip`() {
        val draft = CollectionListDraft(
            title = "Draft",
            query = null,
            sort = CollectionSortSelection.DEFAULT,
            layoutType = null,
            providerId = "mangaupdates",
        )

        val editor = ListEditorState.fromDraft(draft)

        editor.providerId shouldBe "mangaupdates"
        editor.toDraftOrNull()!!.providerId shouldBe "mangaupdates"
    }

    @Test
    fun `quick builder genre include and exclude round trip semantically`() {
        val editor = ListEditorState.empty().copy(
            title = "Genres",
            includeGenre = "Action",
            excludeGenre = "Hentai",
        )

        val draft = editor.toDraftOrNull()!!
        val reopened = ListEditorState.fromDraft(draft)

        reopened.filtersEditable shouldBe true
        reopened.includeGenre shouldBe "Action"
        reopened.excludeGenre shouldBe "Hentai"
        reopened.toDraftOrNull()!!.query!!.normalize() shouldBe draft.query!!.normalize()
    }

    @Test
    fun `provider custom simple predicate stays editable and round trips through extra terms`() {
        val query = QueryExpression.Predicate(
            QueryField.Custom("hikka.only_translated"),
            QueryOperator.EQUALS,
            QueryValue.of(true),
        )
        val draft = CollectionListDraft(
            title = "Translated",
            query = query,
            sort = CollectionSortSelection.DEFAULT,
            layoutType = null,
            providerId = "hikka",
        )

        val editor = ListEditorState.fromDraft(draft)

        editor.filtersEditable shouldBe true
        editor.extraTerms shouldBe listOf(query)
        editor.toDraftOrNull()!!.query shouldBe query
    }

    @Test
    fun `simple standard field outside legacy controls stays editable as extra term`() {
        val query = QueryExpression.Predicate(
            QueryField.PUBLISHER,
            QueryOperator.EQUALS,
            QueryValue.of("Shueisha"),
        )

        val editor = ListEditorState.from(list(query))

        editor.filtersEditable shouldBe true
        editor.extraTerms shouldBe listOf(query)
        editor.toDraftOrNull()!!.query shouldBe query
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
            sort = CollectionSortSelection(
            CollectionSortKey.Standard.RATING,
            CollectionSortDirection.DESC,
        ),
        ).toDraftOrNull()!!.let { draft ->
            draft.title shouldBe "Renamed"
            draft.sort shouldBe CollectionSortSelection(
            CollectionSortKey.Standard.RATING,
            CollectionSortDirection.DESC,
        )
            draft.query shouldBe query
        }
    }

    @Test
    fun `unsupported simple operator remains preserved instead of dropped`() {
        val query = QueryExpression.Predicate(
            QueryField.GENRE,
            QueryOperator.BETWEEN,
            QueryValue.range(QueryValue.of("Action"), QueryValue.of("Drama")),
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
        sort = CollectionSortSelection.DEFAULT,
        sortOrder = 0,
        origin = CollectionOrigin.USER,
        createdAt = 1,
        updatedAt = 1,
    )
}
