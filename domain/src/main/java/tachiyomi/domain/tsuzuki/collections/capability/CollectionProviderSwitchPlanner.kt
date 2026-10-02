package tachiyomi.domain.tsuzuki.collections.capability

import tachiyomi.domain.tsuzuki.collections.model.CollectionSortKey
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortSelection
import tachiyomi.domain.tsuzuki.collections.planner.QueryPlanner
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression

sealed interface CollectionProviderSwitchPlan {
    data class Ready(
        val targetProviderId: String,
        val query: QueryExpression?,
        val removedExpressions: List<QueryExpression>,
        val sort: CollectionSortSelection?,
        val removedSort: CollectionSortSelection?,
    ) : CollectionProviderSwitchPlan {
        val requiresConfirmation: Boolean
            get() = removedExpressions.isNotEmpty() || removedSort != null

        val isLossless: Boolean
            get() = !requiresConfirmation
    }

    data class Blocked(
        val targetProviderId: String,
        val reason: CollectionProviderSwitchBlockReason,
    ) : CollectionProviderSwitchPlan
}

enum class CollectionProviderSwitchBlockReason {
    COMPLEX_QUERY,
}

object CollectionProviderSwitchPlanner {

    fun plan(
        sourceProviderId: String,
        target: ProviderQueryCapabilities,
        query: QueryExpression?,
        sort: CollectionSortSelection,
    ): CollectionProviderSwitchPlan {
        require(sourceProviderId.isNotBlank()) { "Source Collection providerId cannot be blank" }

        val normalized = query?.normalize()
        if (normalized != null && !normalized.isVisuallySwitchable()) {
            return CollectionProviderSwitchPlan.Blocked(
                targetProviderId = target.providerId,
                reason = CollectionProviderSwitchBlockReason.COMPLEX_QUERY,
            )
        }

        val removed = mutableListOf<QueryExpression>()
        val preserved = mutableListOf<QueryExpression>()

        normalized.simpleTerms().forEach { term ->
            if (term.canSurvive(target) && candidateRemainsExecutable(preserved, term, target)) {
                preserved += term
            } else {
                removed += term
            }
        }

        val preservedQuery = preserved.toConjunction()
        val preservesSort = sort.canSurvive(
            sourceProviderId = sourceProviderId,
            target = target,
        )

        return CollectionProviderSwitchPlan.Ready(
            targetProviderId = target.providerId,
            query = preservedQuery,
            removedExpressions = removed.toList(),
            sort = sort.takeIf { preservesSort },
            removedSort = sort.takeUnless { preservesSort },
        )
    }

    private fun QueryExpression.canSurvive(target: ProviderQueryCapabilities): Boolean {
        val descriptor = target.descriptor
        if (!descriptor.covers(this)) return false
        return isExecutable(this, target)
    }

    private fun candidateRemainsExecutable(
        preserved: List<QueryExpression>,
        candidate: QueryExpression,
        target: ProviderQueryCapabilities,
    ): Boolean {
        val expression = (preserved + candidate).toConjunction() ?: return true
        return isExecutable(expression, target)
    }

    private fun isExecutable(
        expression: QueryExpression,
        target: ProviderQueryCapabilities,
    ): Boolean {
        val plan = QueryPlanner.plan(
            expression = expression,
            capabilities = target,
        )
        val residual = plan.residualExpression
        return residual == null || target.descriptor.supportsResidual(residual)
    }

    private fun CollectionSortSelection.canSurvive(
        sourceProviderId: String,
        target: ProviderQueryCapabilities,
    ): Boolean {
        val providerKey = key as? CollectionSortKey.Provider
        if (providerKey != null && sourceProviderId != target.providerId) return false

        return target.descriptor.supports(this) && target.canPushSort(this)
    }

    private fun CollectionProviderDescriptor.covers(expression: QueryExpression): Boolean = when (expression) {
        is QueryExpression.Predicate -> supports(expression)
        is QueryExpression.Not -> {
            val predicate = expression.expression as? QueryExpression.Predicate ?: return false
            filters.any { capability ->
                capability.accepts(predicate) &&
                    capability.multiValueMode in setOf(
                        MultiValueMode.INCLUDE_EXCLUDE,
                        MultiValueMode.PROVIDER_NATIVE,
                    )
            }
        }
        is QueryExpression.All -> expression.expressions.all(::covers)
        is QueryExpression.Any -> false
    }

    private fun QueryExpression.isVisuallySwitchable(): Boolean = when (this) {
        is QueryExpression.Predicate -> true
        is QueryExpression.Not -> expression is QueryExpression.Predicate
        is QueryExpression.All -> expressions.all { child ->
            child is QueryExpression.Predicate ||
                (child is QueryExpression.Not && child.expression is QueryExpression.Predicate)
        }
        is QueryExpression.Any -> false
    }

    private fun QueryExpression?.simpleTerms(): List<QueryExpression> = when (this) {
        null -> emptyList()
        is QueryExpression.All -> expressions
        else -> listOf(this)
    }

    private fun List<QueryExpression>.toConjunction(): QueryExpression? = when (size) {
        0 -> null
        1 -> first()
        else -> QueryExpression.All(this).normalize()
    }
}
