package eu.kanade.tachiyomi.data.collections.provider.kitsu

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.collections.planner.QueryPlanner
import tachiyomi.domain.tsuzuki.collections.planner.SortPlan
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

class KitsuCapabilityTest {

    // 9. Capability declarations do not infer support from CatalogQuery field existence
    @Test
    fun `test 9 capability declarations do not infer support from CatalogQuery field existence`() {
        // CatalogQuery defines genres: List<String> = emptyList()
        val dummyQuery = CatalogQuery(genres = listOf("Action"))
        dummyQuery.genres shouldNotBe emptyList<String>()

        // BUT KitsuQueryCapabilities explicitly rejects genre pushdown!
        val canPushGenre = KitsuQueryCapabilities.canPushPredicate(
            field = QueryField.GENRE,
            operator = QueryOperator.EQUALS,
            value = QueryValue.of("Action"),
        )
        canPushGenre shouldBe false
    }

    // 10. Kitsu genres, scores, tags remain non-pushable under current implementation
    @Test
    fun `test 10 kitsu genres, scores, tags remain non-pushable`() {
        KitsuQueryCapabilities.canPushPredicate(
            field = QueryField.GENRE,
            operator = QueryOperator.EQUALS,
            value = QueryValue.of("Action"),
        ) shouldBe false

        KitsuQueryCapabilities.canPushPredicate(
            field = QueryField.SCORE,
            operator = QueryOperator.GREATER_OR_EQUAL,
            value = QueryValue.of(80L),
        ) shouldBe false

        KitsuQueryCapabilities.canPushPredicate(
            field = QueryField.TAG,
            operator = QueryOperator.CONTAINS,
            value = QueryValue.of("isekai"),
        ) shouldBe false

        KitsuQueryCapabilities.canPushPredicate(
            field = QueryField.AUTHOR,
            operator = QueryOperator.EQUALS,
            value = QueryValue.of("Oda"),
        ) shouldBe false
    }

    @Test
    fun `test kitsu supports text query and ongoing status pushdown`() {
        val pushText = KitsuQueryCapabilities.canPushPredicate(
            field = QueryField.Custom("query"),
            operator = QueryOperator.EQUALS,
            value = QueryValue.of("Berserk"),
        )
        pushText shouldBe true

        val pushTitle = KitsuQueryCapabilities.canPushPredicate(
            field = QueryField.Custom("title"),
            operator = QueryOperator.CONTAINS,
            value = QueryValue.of("Berserk"),
        )
        pushTitle shouldBe true

        val pushOngoing = KitsuQueryCapabilities.canPushPredicate(
            field = QueryField.STATUS,
            operator = QueryOperator.EQUALS,
            value = QueryValue.of("ongoing"),
        )
        pushOngoing shouldBe true

        val pushCompleted = KitsuQueryCapabilities.canPushPredicate(
            field = QueryField.STATUS,
            operator = QueryOperator.EQUALS,
            value = QueryValue.of("completed"),
        )
        pushCompleted shouldBe true

        val pushCancelled = KitsuQueryCapabilities.canPushPredicate(
            field = QueryField.STATUS,
            operator = QueryOperator.EQUALS,
            value = QueryValue.of("cancelled"),
        )
        // Cancelled is mapped to null by Kitsu provider, so it cannot be pushed!
        pushCancelled shouldBe false
    }

    @Test
    fun `test kitsu query compiler produces CatalogQuery`() {
        val queryExpr = QueryExpression.All(
            QueryExpression.Predicate(
                field = QueryField.Custom("query"),
                operator = QueryOperator.EQUALS,
                value = QueryValue.of("Monster"),
            ),
            QueryExpression.Predicate(
                field = QueryField.STATUS,
                operator = QueryOperator.EQUALS,
                value = QueryValue.of("ongoing"),
            ),
            QueryExpression.Predicate(
                field = QueryField.GENRE,
                operator = QueryOperator.EQUALS,
                value = QueryValue.of("Mystery"),
            ),
        )

        val plan = QueryPlanner.plan(queryExpr, KitsuQueryCapabilities, CatalogSort.RATING_DESC)

        // Pushdown contains text query and status; genre is residual
        val catalogQuery = KitsuQueryCompiler.compile(
            pushdownExpression = plan.pushdownExpression,
            sort = (plan.sortPlan as SortPlan.RemoteExact).sort,
            offset = 10,
            limit = 20,
        )

        catalogQuery.query shouldBe "Monster"
        catalogQuery.status shouldBe CatalogItemStatus.ONGOING
        catalogQuery.sort shouldBe CatalogSort.RATING_DESC
        catalogQuery.genres shouldBe emptyList() // Not pushed
        catalogQuery.offset shouldBe 10
        catalogQuery.limit shouldBe 20

        // Residual still holds genre
        plan.residualExpression shouldBe QueryExpression.Predicate(
            field = QueryField.GENRE,
            operator = QueryOperator.EQUALS,
            value = QueryValue.of("Mystery"),
        )
    }
}
