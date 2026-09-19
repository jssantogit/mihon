package tachiyomi.data.tsuzuki.kitsu.collections

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
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

    @Test
    fun `capability declarations do not infer support from CatalogQuery field existence`() {
        val dummyQuery = CatalogQuery(genres = listOf("Action"))
        dummyQuery.genres shouldNotBe emptyList<String>()

        KitsuQueryCapabilities.canPushPredicate(
            field = QueryField.GENRE,
            operator = QueryOperator.EQUALS,
            value = QueryValue.of("Action"),
        ) shouldBe false
    }

    @Test
    fun `genres scores tags and authors remain non-pushable`() {
        KitsuQueryCapabilities.canPushPredicate(
            QueryField.GENRE,
            QueryOperator.EQUALS,
            QueryValue.of("Action"),
        ) shouldBe false
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
            QueryValue.of("ongoing"),
        ) shouldBe true

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

        KitsuQueryCapabilities.canPushSort(CatalogSort.POPULARITY_DESC) shouldBe true
        KitsuQueryCapabilities.canPushSort(CatalogSort.RATING_DESC) shouldBe true
        KitsuQueryCapabilities.canPushSort(CatalogSort.UPDATED_DESC) shouldBe true
        KitsuQueryCapabilities.canPushSort(CatalogSort.RELEVANCE) shouldBe false
    }

    @Test
    fun `planner keeps text and genre residual while compiling exact status`() {
        val text = QueryExpression.Predicate(
            field = QueryField.Custom("query"),
            operator = QueryOperator.EQUALS,
            value = QueryValue.of("Monster"),
        )
        val genre = QueryExpression.Predicate(
            field = QueryField.GENRE,
            operator = QueryOperator.EQUALS,
            value = QueryValue.of("Mystery"),
        )
        val queryExpression = QueryExpression.All(text, ongoing, genre)

        val plan = QueryPlanner.plan(queryExpression, KitsuQueryCapabilities, CatalogSort.RATING_DESC)

        plan.pushdownExpression shouldBe ongoing
        plan.residualExpression shouldBe QueryExpression.All(text, genre).normalize()

        val catalogQuery = KitsuQueryCompiler.compile(
            pushdownExpression = plan.pushdownExpression,
            sort = (plan.sortPlan as SortPlan.RemoteExact).sort,
            offset = 10,
            limit = 20,
        )

        catalogQuery.query shouldBe null
        catalogQuery.status shouldBe CatalogItemStatus.ONGOING
        catalogQuery.sort shouldBe CatalogSort.RATING_DESC
        catalogQuery.genres shouldBe emptyList()
        catalogQuery.offset shouldBe 10
        catalogQuery.limit shouldBe 20
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
                sort = CatalogSort.RELEVANCE,
            )
        }
    }
}
