package tachiyomi.domain.tsuzuki.collections.planner

import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.collections.capability.ProviderQueryCapabilities
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryNormalizer

/**
 * Capability-aware Query Planner.
 *
 * Pushes only expressions the provider explicitly declares representable with exact semantics.
 * Unsupported expressions remain residual and are never silently discarded.
 */
object QueryPlanner {

    fun plan(
        expression: QueryExpression?,
        capabilities: ProviderQueryCapabilities,
        requestedSort: CatalogSort = CatalogSort.POPULARITY_DESC,
    ): QueryPlan {
        val sortPlan = planSort(requestedSort, capabilities)

        if (expression == null) {
            return QueryPlan(
                pushdownExpression = null,
                residualExpression = null,
                sortPlan = sortPlan,
            )
        }

        val normalized = QueryNormalizer.normalize(expression)
        val (pushdown, residual) = splitExpression(normalized, capabilities)

        return QueryPlan(
            pushdownExpression = pushdown?.let(QueryNormalizer::normalize),
            residualExpression = residual?.let(QueryNormalizer::normalize),
            sortPlan = sortPlan,
        )
    }

    private fun planSort(requestedSort: CatalogSort, capabilities: ProviderQueryCapabilities): SortPlan {
        return if (capabilities.canPushSort(requestedSort)) {
            SortPlan.RemoteExact(requestedSort)
        } else {
            SortPlan.UnsupportedForGlobalOrdering(
                requestedSort = requestedSort,
                fallbackRemoteSort = CatalogSort.POPULARITY_DESC,
            )
        }
    }

    private fun splitExpression(
        expression: QueryExpression,
        capabilities: ProviderQueryCapabilities,
    ): Pair<QueryExpression?, QueryExpression?> {
        if (capabilities.canPushExpression(expression)) {
            return expression to null
        }

        return when (expression) {
            is QueryExpression.Predicate,
            is QueryExpression.Any,
            is QueryExpression.Not,
            -> null to expression

            is QueryExpression.All -> splitAll(expression, capabilities)
        }
    }

    /**
     * Conjunctions permit safe partial pushdown, but the accumulated remote conjunction must itself
     * remain exactly representable. If adding another child exceeds provider expressiveness, that
     * child returns to the residual side rather than being silently approximated.
     */
    private fun splitAll(
        expression: QueryExpression.All,
        capabilities: ProviderQueryCapabilities,
    ): Pair<QueryExpression?, QueryExpression?> {
        var pushdown: QueryExpression? = null
        val residualChildren = mutableListOf<QueryExpression>()

        for (child in expression.expressions) {
            val (childPushdown, childResidual) = splitExpression(child, capabilities)

            if (childPushdown != null) {
                val candidate = if (pushdown == null) {
                    childPushdown
                } else {
                    QueryExpression.All(pushdown, childPushdown).normalize()
                }

                if (capabilities.canPushExpression(candidate)) {
                    pushdown = candidate
                } else {
                    residualChildren += childPushdown
                }
            }

            if (childResidual != null) {
                residualChildren += childResidual
            }
        }

        return pushdown to combineAll(residualChildren)
    }

    private fun combineAll(expressions: List<QueryExpression>): QueryExpression? = when (expressions.size) {
        0 -> null
        1 -> expressions.first()
        else -> QueryExpression.All(expressions).normalize()
    }
}
