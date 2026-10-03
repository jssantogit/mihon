package tachiyomi.domain.tsuzuki.collections.planner

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.collections.capability.CollectionPagingCapability
import tachiyomi.domain.tsuzuki.collections.capability.CollectionPagingMode
import tachiyomi.domain.tsuzuki.collections.capability.CollectionProviderDescriptor
import tachiyomi.domain.tsuzuki.collections.capability.CollectionProviderScope
import tachiyomi.domain.tsuzuki.collections.capability.ProviderQueryCapabilities
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortDirection
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortKey
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortSelection
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

class QueryPlannerTest {

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

    private val fakeCapabilities = object : ProviderQueryCapabilities {
        override val descriptor: CollectionProviderDescriptor = descriptor("fake")

        override fun canPushSort(sort: CollectionSortSelection): Boolean {
            return sort == popularityDesc || sort == ratingDesc
        }

        override fun canPushPredicate(field: QueryField, operator: QueryOperator, value: QueryValue): Boolean {
            if (field == QueryField.STATUS && operator == QueryOperator.EQUALS && value == QueryValue.of("ongoing")) {
                return true
            }
            if (field == QueryField.Custom("query") && operator == QueryOperator.EQUALS) {
                return true
            }
            return false
        }

        override fun canPushExpression(expression: QueryExpression): Boolean = when (expression) {
            is QueryExpression.Predicate -> canPushPredicate(
                expression.field,
                expression.operator,
                expression.value,
            )
            is QueryExpression.All -> expression.expressions.all(::canPushExpression)
            is QueryExpression.Any -> expression.expressions.all(::canPushExpression)
            is QueryExpression.Not -> false
        }
    }

    private val predicateOnlyCapabilities = object : ProviderQueryCapabilities {
        override val descriptor: CollectionProviderDescriptor = descriptor("predicate-only")
        override fun canPushSort(sort: CollectionSortSelection): Boolean = sort == popularityDesc

        override fun canPushPredicate(field: QueryField, operator: QueryOperator, value: QueryValue): Boolean {
            return (field == QueryField.STATUS || field == QueryField.Custom("query")) &&
                operator == QueryOperator.EQUALS
        }
    }

    private val pushableStatus = QueryExpression.Predicate(
        field = QueryField.STATUS,
        operator = QueryOperator.EQUALS,
        value = QueryValue.of("ongoing"),
    )

    private val pushableQuery = QueryExpression.Predicate(
        field = QueryField.Custom("query"),
        operator = QueryOperator.EQUALS,
        value = QueryValue.of("naruto"),
    )

    private val nonPushableGenre = QueryExpression.Predicate(
        field = QueryField.GENRE,
        operator = QueryOperator.EQUALS,
        value = QueryValue.of("Action"),
    )

    private val nonPushableScore = QueryExpression.Predicate(
        field = QueryField.SCORE,
        operator = QueryOperator.GREATER_OR_EQUAL,
        value = QueryValue.of(80L),
    )

    @Test
    fun `fully pushable predicate`() {
        val plan = QueryPlanner.plan(pushableStatus, fakeCapabilities)
        plan.isFullyPushdown shouldBe true
        plan.pushdownExpression shouldBe pushableStatus
        plan.residualExpression shouldBe null
    }

    @Test
    fun `fully residual predicate`() {
        val plan = QueryPlanner.plan(nonPushableGenre, fakeCapabilities)
        plan.isFullyResidual shouldBe true
        plan.pushdownExpression shouldBe null
        plan.residualExpression shouldBe nonPushableGenre
    }

    @Test
    fun `ALL with mixed pushable and residual children`() {
        val plan = QueryPlanner.plan(
            QueryExpression.All(pushableStatus, nonPushableGenre),
            fakeCapabilities,
        )
        plan.pushdownExpression shouldBe pushableStatus
        plan.residualExpression shouldBe nonPushableGenre
    }

    @Test
    fun `nested ALL structures preserve exact partition`() {
        val expression = QueryExpression.All(
            pushableStatus,
            QueryExpression.All(
                pushableQuery,
                QueryExpression.All(nonPushableGenre, nonPushableScore),
            ),
        )
        val plan = QueryPlanner.plan(expression, fakeCapabilities)

        plan.pushdownExpression shouldBe QueryExpression.All(pushableStatus, pushableQuery).normalize()
        plan.residualExpression shouldBe QueryExpression.All(nonPushableGenre, nonPushableScore).normalize()
    }

    @Test
    fun `ANY is pushed only when provider explicitly supports complete expression`() {
        val expression = QueryExpression.Any(pushableStatus, pushableQuery)
        val plan = QueryPlanner.plan(expression, fakeCapabilities)

        plan.pushdownExpression shouldBe expression.normalize()
        plan.residualExpression shouldBe null
    }

    @Test
    fun `ANY support is not inferred from individually pushable predicates`() {
        val expression = QueryExpression.Any(pushableStatus, pushableQuery)
        val plan = QueryPlanner.plan(expression, predicateOnlyCapabilities)

        plan.pushdownExpression shouldBe null
        plan.residualExpression shouldBe expression.normalize()
    }

    @Test
    fun `partial ANY remains entirely residual`() {
        val expression = QueryExpression.Any(pushableStatus, nonPushableGenre)
        val plan = QueryPlanner.plan(expression, fakeCapabilities)

        plan.pushdownExpression shouldBe null
        plan.residualExpression shouldBe expression.normalize()
    }

    @Test
    fun `NOT remains residual without explicit whole-expression capability`() {
        val expression = QueryExpression.Not(pushableStatus)
        val plan = QueryPlanner.plan(expression, predicateOnlyCapabilities)

        plan.pushdownExpression shouldBe null
        plan.residualExpression shouldBe expression.normalize()
    }

    @Test
    fun `unrepresentable conjunction capacity moves excess predicate to residual`() {
        val expression = QueryExpression.All(pushableStatus, pushableQuery)
        val plan = QueryPlanner.plan(expression, predicateOnlyCapabilities)

        val pushed = plan.pushdownExpression.shouldBeInstanceOf<QueryExpression.Predicate>()
        val residual = plan.residualExpression.shouldBeInstanceOf<QueryExpression.Predicate>()

        setOf(
            pushed.toCanonicalString(),
            residual.toCanonicalString(),
        ) shouldBe expression.expressions.map { it.toCanonicalString() }.toSet()
    }

    @Test
    fun `no predicate is silently discarded`() {
        val expression = QueryExpression.All(
            pushableStatus,
            QueryExpression.Any(pushableQuery, nonPushableGenre),
            nonPushableScore,
        )
        val plan = QueryPlanner.plan(expression, fakeCapabilities)

        plan.pushdownExpression shouldBe pushableStatus
        val residual = plan.residualExpression.shouldBeInstanceOf<QueryExpression.All>()
        residual.expressions.size shouldBe 2
        residual.expressions.contains(nonPushableScore) shouldBe true
        residual.expressions.contains(QueryExpression.Any(pushableQuery, nonPushableGenre).normalize()) shouldBe true
    }

    @Test
    fun `unsupported global sort is explicit`() {
        val supported = QueryPlanner.plan(pushableStatus, fakeCapabilities, popularityDesc)
        supported.sortPlan shouldBe SortPlan.RemoteExact(popularityDesc)

        val unsupported = QueryPlanner.plan(pushableStatus, fakeCapabilities, updatedDesc)
        val details = unsupported.sortPlan.shouldBeInstanceOf<SortPlan.UnsupportedForGlobalOrdering>()
        details.requestedSort shouldBe updatedDesc
        details.fallbackRemoteSort shouldBe popularityDesc
    }

    @Test
    fun `planner normalization preserves semantics`() {
        val unnormalized = QueryExpression.All(
            nonPushableGenre,
            pushableStatus,
            nonPushableGenre,
        )
        val plan = QueryPlanner.plan(unnormalized, fakeCapabilities)

        plan.pushdownExpression shouldBe pushableStatus
        plan.residualExpression shouldBe nonPushableGenre
    }

    @Test
    fun `equivalent normalized queries produce equivalent plans`() {
        val queryA = QueryExpression.All(pushableStatus, nonPushableGenre)
        val queryB = QueryExpression.All(nonPushableGenre, pushableStatus)

        QueryPlanner.plan(queryA, fakeCapabilities) shouldBe QueryPlanner.plan(queryB, fakeCapabilities)
    }

    @Test
    fun `provider-specific capability does not leak into core query AST`() {
        val expression = QueryExpression.All(pushableStatus, nonPushableGenre)
        expression.toCanonicalString() shouldBe "ALL(status EQUALS \"ongoing\", genre EQUALS \"Action\")"
    }

    private fun descriptor(providerId: String) = CollectionProviderDescriptor(
        providerId = providerId,
        displayName = providerId,
        scope = CollectionProviderScope.GLOBAL,
        filters = emptyList(),
        sorts = emptyList(),
        paging = CollectionPagingCapability(
            mode = CollectionPagingMode.OFFSET,
            maxPageSize = 20,
        ),
    )
}
