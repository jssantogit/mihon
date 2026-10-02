package tachiyomi.data.tsuzuki.kitsu.collections

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortDirection
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortKey
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortSelection
import tachiyomi.domain.tsuzuki.collections.planner.QueryPlanner
import tachiyomi.domain.tsuzuki.collections.planner.SortPlan
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

class KitsuCapabilityTest {

    private val popularityDesc = CollectionSortSelection(
        CollectionSortKey.Standard.POPULARITY,
        CollectionSortDirection.DESC,
    )
    private val ratingDesc = CollectionSortSelection(
        CollectionSortKey.Standard.RATING,
        CollectionSortDirection.DESC,
    )
    private val updatedDesc = CollectionSortSelection(
        CollectionSortKey.Standard.UPDATED,
        CollectionSortDirection.DESC,
    )
    private val relevance = CollectionSortSelection(
        CollectionSortKey.Standard.RELEVANCE,
        direction = null,
    )

    private val ongoing = QueryExpression.Predicate(
        field = QueryField.STATUS,
        operator = QueryOperator.EQUALS,
        value = QueryValue.of("ongoing"),
    )

    private val completed = QueryExpression.Predicate(
        field = QueryField.STATUS,
        operator = QueryOperator.EQUALS,
        value = QueryValue.of("completed"),
    )

    private val manga = QueryExpression.Predicate(
        field = QueryField.WORK_TYPE,
        operator = QueryOperator.EQUALS,
        value = QueryValue.of("MANGA"),
    )

    private val romance = QueryExpression.Predicate(
        field = QueryField.GENRE,
        operator = QueryOperator.CONTAINS,
        value = QueryValue.of("Romance"),
    )

    @Test
    fun `Kitsu advertises exact status subtype and genre filters`() {
        KitsuQueryCapabilities.canPushPredicate(
            QueryField.STATUS,
            QueryOperator.EQUALS,
            QueryValue.of("ongoing"),
        ) shouldBe true
        KitsuQueryCapabilities.canPushPredicate(
            QueryField.WORK_TYPE,
            QueryOperator.EQUALS,
            QueryValue.of("manga"),
        ) shouldBe true
        KitsuQueryCapabilities.canPushPredicate(
            QueryField.GENRE,
            QueryOperator.CONTAINS,
            QueryValue.of("Romance"),
        ) shouldBe true
        KitsuQueryCapabilities.canPushPredicate(
            QueryField.GENRE,
            QueryOperator.EQUALS,
            QueryValue.of("Action"),
        ) shouldBe true

        KitsuQueryCapabilities.canPushPredicate(
            QueryField.WORK_TYPE,
            QueryOperator.EQUALS,
            QueryValue.of("WEBTOON"),
        ) shouldBe false
    }

    @Test
    fun `scores tags authors and genre exclusion remain non-pushable`() {
        KitsuQueryCapabilities.canPushPredicate(
            QueryField.SCORE,
            QueryOperator.GREATER_OR_EQUAL,
            QueryValue.of(80L),
        ) shouldBe false
        KitsuQueryCapabilities.canPushPredicate(
            QueryField.TAG,
            QueryOperator.CONTAINS,
            QueryValue.of("isekai"),
        ) shouldBe false
        KitsuQueryCapabilities.canPushPredicate(
            QueryField.AUTHOR,
            QueryOperator.EQUALS,
            QueryValue.of("Oda"),
        ) shouldBe false

        KitsuQueryCapabilities.canPushExpression(
            QueryExpression.Not(romance),
        ) shouldBe false
    }

    @Test
    fun `Kitsu exposes only proven exact predicate pushdown`() {
        KitsuQueryCapabilities.canPushPredicate(
            QueryField.Custom("query"),
            QueryOperator.EQUALS,
            QueryValue.of("Berserk"),
        ) shouldBe false

        KitsuQueryCapabilities.canPushPredicate(
            QueryField.Custom("title"),
            QueryOperator.CONTAINS,
            QueryValue.of("Berserk"),
        ) shouldBe false

        KitsuQueryCapabilities.canPushPredicate(
            QueryField.STATUS,
            QueryOperator.EQUALS,
            QueryValue.of("completed"),
        ) shouldBe true

        KitsuQueryCapabilities.canPushPredicate(
            QueryField.STATUS,
            QueryOperator.EQUALS,
            QueryValue.of("cancelled"),
        ) shouldBe false

        KitsuQueryCapabilities.canPushSort(popularityDesc) shouldBe true
        KitsuQueryCapabilities.canPushSort(ratingDesc) shouldBe true
        KitsuQueryCapabilities.canPushSort(updatedDesc) shouldBe true
        KitsuQueryCapabilities.canPushSort(relevance) shouldBe false
    }

    @Test
    fun `planner pushes romance manga conjunction and keeps unsupported text residual`() {
        val text = QueryExpression.Predicate(
            field = QueryField.Custom("query"),
            operator = QueryOperator.EQUALS,
            value = QueryValue.of("Monster"),
        )
        val queryExpression = QueryExpression.All(text, manga, romance)

        val plan = QueryPlanner.plan(queryExpression, KitsuQueryCapabilities, ratingDesc)

        plan.pushdownExpression shouldBe QueryExpression.All(manga, romance).normalize()
        plan.residualExpression shouldBe text

        val catalogQuery = KitsuQueryCompiler.compile(
            pushdownExpression = plan.pushdownExpression,
            sort = (plan.sortPlan as SortPlan.RemoteExact).sort,
            offset = 10,
            limit = 20,
        )

        catalogQuery.query shouldBe null
        catalogQuery.status shouldBe null
        catalogQuery.format shouldBe CatalogItemFormat.MANGA
        catalogQuery.sort shouldBe CatalogSort.RATING_DESC
        catalogQuery.genres shouldBe listOf("Romance")
        catalogQuery.offset shouldBe 10
        catalogQuery.limit shouldBe 20
    }

    @Test
    fun `status subtype and genre can be compiled together`() {
        val expression = QueryExpression.All(ongoing, manga, romance)

        KitsuQueryCapabilities.canPushExpression(expression) shouldBe true

        val query = KitsuQueryCompiler.compile(expression)

        query.status shouldBe CatalogItemStatus.ONGOING
        query.format shouldBe CatalogItemFormat.MANGA
        query.genres shouldBe listOf("Romance")
    }

    @Test
    fun `ANY of individually pushable statuses remains residual without OR capability`() {
        val expression = QueryExpression.Any(ongoing, completed)
        val plan = QueryPlanner.plan(expression, KitsuQueryCapabilities)

        plan.pushdownExpression shouldBe null
        plan.residualExpression shouldBe expression.normalize()
    }

    @Test
    fun `single status slot cannot silently absorb two status predicates`() {
        val expression = QueryExpression.All(ongoing, completed)
        val plan = QueryPlanner.plan(expression, KitsuQueryCapabilities)

        plan.pushdownExpression.shouldBeInstanceOf<QueryExpression.Predicate>()
        plan.residualExpression.shouldBeInstanceOf<QueryExpression.Predicate>()

        setOf(
            plan.pushdownExpression!!.toCanonicalString(),
            plan.residualExpression!!.toCanonicalString(),
        ) shouldBe expression.expressions.map { it.toCanonicalString() }.toSet()
    }

    @Test
    fun `compiler fails closed for unsupported compound expressions`() {
        shouldThrow<IllegalArgumentException> {
            KitsuQueryCompiler.compile(
                pushdownExpression = QueryExpression.Any(ongoing, completed),
            )
        }
    }

    @Test
    fun `compiler rejects unsupported relevance ordering`() {
        shouldThrow<IllegalArgumentException> {
            KitsuQueryCompiler.compile(
                pushdownExpression = ongoing,
                sort = relevance,
            )
        }
    }
}
