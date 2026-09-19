package tachiyomi.domain.tsuzuki.collections.planner

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.collections.capability.ProviderQueryCapabilities
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

class QueryPlannerTest {

    private val fakeCapabilities = object : ProviderQueryCapabilities {
        override val providerId: String = "fake"
        override val supportsOffsetPaging: Boolean = true
        override val maxPageSize: Int = 20

        override fun canPushSort(sort: CatalogSort): Boolean {
            return sort == CatalogSort.POPULARITY_DESC || sort == CatalogSort.RATING_DESC
        }

        override fun canPushPredicate(field: QueryField, operator: QueryOperator, value: QueryValue): Boolean {
            // Only push STATUS = "ongoing" and Custom("query")
            if (field == QueryField.STATUS && operator == QueryOperator.EQUALS && value == QueryValue.of("ongoing")) {
                return true
            }
            if (field == QueryField.Custom("query") && operator == QueryOperator.EQUALS) {
                return true
            }
            return false
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

    // 1. Fully pushable predicate
    @Test
    fun `test 1 fully pushable predicate`() {
        val plan = QueryPlanner.plan(pushableStatus, fakeCapabilities)

        plan.isFullyPushdown shouldBe true
        plan.isFullyResidual shouldBe false
        plan.pushdownExpression shouldBe pushableStatus
        plan.residualExpression shouldBe null
    }

    // 2. Fully residual predicate
    @Test
    fun `test 2 fully residual predicate`() {
        val plan = QueryPlanner.plan(nonPushableGenre, fakeCapabilities)

        plan.isFullyPushdown shouldBe false
        plan.isFullyResidual shouldBe true
        plan.pushdownExpression shouldBe null
        plan.residualExpression shouldBe nonPushableGenre
    }

    // 3. ALL with mixed pushable and residual children (partial pushdown)
    @Test
    fun `test 3 ALL with mixed pushable and residual children`() {
        val expr = QueryExpression.All(pushableStatus, nonPushableGenre)
        val plan = QueryPlanner.plan(expr, fakeCapabilities)

        plan.pushdownExpression shouldBe pushableStatus
        plan.residualExpression shouldBe nonPushableGenre
    }

    // 4. Nested ALL structures
    @Test
    fun `test 4 nested ALL structures`() {
        val expr = QueryExpression.All(
            pushableStatus,
            QueryExpression.All(
                pushableQuery,
                QueryExpression.All(nonPushableGenre, nonPushableScore),
            ),
        )
        val plan = QueryPlanner.plan(expr, fakeCapabilities)

        // Pushdown should have both pushable children normalized into ALL
        plan.pushdownExpression shouldBe QueryExpression.All(pushableStatus, pushableQuery).normalize()
        plan.residualExpression shouldBe QueryExpression.All(nonPushableGenre, nonPushableScore).normalize()
    }

    // 5. ANY where all children are pushable
    @Test
    fun `test 5 ANY where all children are pushable`() {
        val expr = QueryExpression.Any(pushableStatus, pushableQuery)
        val plan = QueryPlanner.plan(expr, fakeCapabilities)

        plan.pushdownExpression shouldBe expr.normalize()
        plan.residualExpression shouldBe null
    }

    // 6. ANY where only some children are pushable MUST remain entirely residual
    @Test
    fun `test 6 ANY where only some children are pushable remains entirely residual`() {
        val expr = QueryExpression.Any(pushableStatus, nonPushableGenre)
        val plan = QueryPlanner.plan(expr, fakeCapabilities)

        // Semantic invariant: If any child cannot be pushed, pushing partial ANY
        // would drop results matching the other branch!
        plan.pushdownExpression shouldBe null
        plan.residualExpression shouldBe expr.normalize()
    }

    // 7. NOT with unsupported child remains residual
    @Test
    fun `test 7 NOT with unsupported child remains residual`() {
        val expr = QueryExpression.Not(nonPushableGenre)
        val plan = QueryPlanner.plan(expr, fakeCapabilities)

        plan.pushdownExpression shouldBe null
        plan.residualExpression shouldBe expr.normalize()
    }

    // 8. No predicate silently discarded
    @Test
    fun `test 8 no predicate silently discarded`() {
        val expr = QueryExpression.All(
            pushableStatus,
            QueryExpression.Any(pushableQuery, nonPushableGenre),
            nonPushableScore,
        )
        val plan = QueryPlanner.plan(expr, fakeCapabilities)

        plan.pushdownExpression shouldBe pushableStatus
        // The Any and Score should be preserved in residual
        val residual = plan.residualExpression.shouldBeInstanceOf<QueryExpression.All>()
        residual.expressions.size shouldBe 2
        residual.expressions.contains(nonPushableScore) shouldBe true
        residual.expressions.contains(QueryExpression.Any(pushableQuery, nonPushableGenre).normalize()) shouldBe true
    }

    // 11. Unsupported global sort is explicit
    @Test
    fun `test 11 unsupported global sort is explicit`() {
        val supportedPlan = QueryPlanner.plan(pushableStatus, fakeCapabilities, CatalogSort.POPULARITY_DESC)
        supportedPlan.sortPlan shouldBe SortPlan.RemoteExact(CatalogSort.POPULARITY_DESC)

        val unsupportedPlan = QueryPlanner.plan(pushableStatus, fakeCapabilities, CatalogSort.UPDATED_DESC)
        unsupportedPlan.sortPlan.shouldBeInstanceOf<SortPlan.UnsupportedForGlobalOrdering>()
        val sortDetails = unsupportedPlan.sortPlan as SortPlan.UnsupportedForGlobalOrdering
        sortDetails.requestedSort shouldBe CatalogSort.UPDATED_DESC
        sortDetails.fallbackRemoteSort shouldBe CatalogSort.POPULARITY_DESC
    }

    // 12. Planner normalization and ordering preserves semantics
    @Test
    fun `test 12 planner normalization preserves semantics`() {
        val unnormalized = QueryExpression.All(
            nonPushableGenre,
            pushableStatus,
            nonPushableGenre, // duplicate
        )
        val plan = QueryPlanner.plan(unnormalized, fakeCapabilities)

        plan.pushdownExpression shouldBe pushableStatus
        plan.residualExpression shouldBe nonPushableGenre
    }

    // 13. Equivalent normalized queries produce equivalent plans
    @Test
    fun `test 13 equivalent normalized queries produce equivalent plans`() {
        val queryA = QueryExpression.All(pushableStatus, nonPushableGenre)
        val queryB = QueryExpression.All(nonPushableGenre, pushableStatus)

        val planA = QueryPlanner.plan(queryA, fakeCapabilities)
        val planB = QueryPlanner.plan(queryB, fakeCapabilities)

        planA shouldBe planB
    }

    // 14. Provider-specific capability implementation does not leak into core query AST
    @Test
    fun `test 14 provider-specific capability does not leak into core query AST`() {
        // Query AST remains purely neutral
        val expr = QueryExpression.All(pushableStatus, nonPushableGenre)
        expr.toCanonicalString() shouldBe "ALL(status = \"ongoing\", genre = \"Action\")"
    }
}
