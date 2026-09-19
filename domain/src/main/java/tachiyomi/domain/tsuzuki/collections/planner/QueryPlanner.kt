package tachiyomi.domain.tsuzuki.collections.planner

import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.collections.capability.ProviderQueryCapabilities
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryNormalizer

/**
 * Capability-aware Query Planner.
 *
 * Consumes a normalized provider-neutral query AST and catalog provider capabilities,
 * partitioning the AST into a remote pushdown expression and a local residual expression.
 *
 * Semantic Invariants:
 * 1. Semantic Preservation:
 *    - ALL(A, B): If A is pushable and B is not -> pushdown = A, residual = B.
 *    - ANY(A, B): If only A is pushable, A CANNOT be pushed down alone, because pushing A
 *      would eliminate provider results matching B. The entire ANY must remain residual unless ALL
 *      its children are pushable.
 *    - NOT(A): If A is not pushable, the entire NOT(A) remains residual.
 * 2. No Predicate Loss:
 *    - No predicate is silently dropped; every predicate is preserved in either pushdown or residual (or both if needed).
 * 3. Exact Pushdown:
 *    - If no predicates can be pushed down, pushdownExpression is null.
 *    - If all predicates can be pushed down, residualExpression is null.
 * 4. Deterministic Plans:
 *    - Equivalent normalized queries produce identical plans.
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

        val normalizedPushdown = pushdown?.let { QueryNormalizer.normalize(it) }
        val normalizedResidual = residual?.let { QueryNormalizer.normalize(it) }

        return QueryPlan(
            pushdownExpression = normalizedPushdown,
            residualExpression = normalizedResidual,
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
        expr: QueryExpression,
        capabilities: ProviderQueryCapabilities,
    ): Pair<QueryExpression?, QueryExpression?> {
        return when (expr) {
            is QueryExpression.Predicate -> {
                if (capabilities.canPushPredicate(expr.field, expr.operator, expr.value)) {
                    expr to null
                } else {
                    null to expr
                }
            }
            is QueryExpression.All -> {
                val pushableChildren = mutableListOf<QueryExpression>()
                val residualChildren = mutableListOf<QueryExpression>()

                for (child in expr.expressions) {
                    val (pushed, residual) = splitExpression(child, capabilities)
                    if (pushed != null) pushableChildren.add(pushed)
                    if (residual != null) residualChildren.add(residual)
                }

                val pushdown = when (pushableChildren.size) {
                    0 -> null
                    1 -> pushableChildren.first()
                    else -> QueryExpression.All(pushableChildren)
                }

                val residual = when (residualChildren.size) {
                    0 -> null
                    1 -> residualChildren.first()
                    else -> QueryExpression.All(residualChildren)
                }

                pushdown to residual
            }
            is QueryExpression.Any -> {
                // ANY semantics: Cannot push partial children because filtering by a subset
                // would drop items that satisfy the other disjuncts.
                // It can only be pushed down if EVERY child can be fully pushed down with 0 residual!
                val childSplits = expr.expressions.map { splitExpression(it, capabilities) }
                val allFullyPushable = childSplits.all { (pushed, residual) -> pushed != null && residual == null }

                if (allFullyPushable) {
                    val pushedChildren = childSplits.map { it.first!! }
                    QueryExpression.Any(pushedChildren) to null
                } else {
                    null to expr
                }
            }
            is QueryExpression.Not -> {
                // NOT semantics: Negation pushdown is only safe if the inner expression is fully pushable.
                // In general, remote APIs rarely support arbitrary NOT pushdown unless explicitly handled.
                // If the inner expression is completely pushable, can we push NOT?
                // Notice: Unless capabilities explicitly handle NOT, pushing NOT can be hazardous.
                // As per requirement: "If A is not exactly pushable under NOT semantics, the entire Not(A) must remain residual."
                // Even if A is pushable as an affirmative predicate, provider might not support negation.
                // For safety and correctness over aggressive pushdown:
                // If inner is a Predicate, check if operator NOT_EQUALS can be pushed, or keep residual.
                // If inner cannot be pushed or capabilities do not support NOT pushdown, keep NOT residual.
                val (pushed, residual) = splitExpression(expr.expression, capabilities)
                if (pushed != null && residual == null && canPushNegation(expr.expression, capabilities)) {
                    QueryExpression.Not(pushed) to null
                } else {
                    null to expr
                }
            }
        }
    }

    private fun canPushNegation(inner: QueryExpression, capabilities: ProviderQueryCapabilities): Boolean {
        // Negation is only pushable if capabilities explicitly allow it.
        // For standard providers like Kitsu, negation is not supported.
        return false
    }
}
