package tachiyomi.data.tsuzuki.kitsu.collections

import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.collections.capability.ProviderQueryCapabilities
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

/**
 * Exact Collections-query capabilities for the Kitsu catalog provider.
 *
 * Kitsu's existing client exposes filter[text], but that endpoint is a text-search candidate mechanism,
 * not a proven exact implementation of Collections EQUALS or CONTAINS predicate semantics. It is
 * therefore intentionally not advertised as predicate pushdown.
 *
 * The current CatalogQuery contract can represent one exact Kitsu status filter at a time. Compound
 * boolean expressions are not advertised by this adapter.
 */
object KitsuQueryCapabilities : ProviderQueryCapabilities {
    override val providerId: String = "kitsu"

    override val supportsOffsetPaging: Boolean = true

    override val maxPageSize: Int = 20

    override fun canPushSort(sort: CatalogSort): Boolean = when (sort) {
        CatalogSort.POPULARITY_DESC,
        CatalogSort.POPULARITY_ASC,
        CatalogSort.RATING_DESC,
        CatalogSort.RATING_ASC,
        CatalogSort.UPDATED_DESC,
        -> true
        CatalogSort.RELEVANCE -> false
    }

    override fun canPushPredicate(field: QueryField, operator: QueryOperator, value: QueryValue): Boolean {
        if (field != QueryField.STATUS || operator != QueryOperator.EQUALS || value !is QueryValue.StringValue) {
            return false
        }

        return when (value.value.trim().uppercase()) {
            CatalogItemStatus.ONGOING.name,
            CatalogItemStatus.COMPLETED.name,
            -> true
            else -> false
        }
    }
}

/**
 * Compiles only expressions that [KitsuQueryCapabilities] declares exactly representable.
 *
 * Unsupported expressions fail closed instead of being ignored. The planner must retain those
 * expressions as residual work for Block C.
 */
object KitsuQueryCompiler {

    fun compile(
        pushdownExpression: QueryExpression?,
        sort: CatalogSort = CatalogSort.POPULARITY_DESC,
        offset: Int = 0,
        limit: Int = 20,
    ): CatalogQuery {
        require(offset >= 0) { "Kitsu offset must be non-negative" }
        require(limit in 1..KitsuQueryCapabilities.maxPageSize) {
            "Kitsu page limit must be between 1 and ${KitsuQueryCapabilities.maxPageSize}"
        }
        require(KitsuQueryCapabilities.canPushSort(sort)) {
            "Kitsu cannot guarantee exact remote ordering for $sort"
        }

        val status = when (pushdownExpression) {
            null -> null
            is QueryExpression.Predicate -> compileStatusPredicate(pushdownExpression)
            else -> throw IllegalArgumentException(
                "Kitsu cannot compile compound Collections pushdown: ${pushdownExpression.toCanonicalString()}",
            )
        }

        return CatalogQuery(
            query = null,
            sort = sort,
            genres = emptyList(),
            status = status,
            offset = offset,
            limit = limit,
        )
    }

    private fun compileStatusPredicate(predicate: QueryExpression.Predicate): CatalogItemStatus {
        require(KitsuQueryCapabilities.canPushExpression(predicate)) {
            "Kitsu cannot compile predicate: ${predicate.toCanonicalString()}"
        }

        val value = (predicate.value as QueryValue.StringValue).value.trim().uppercase()
        return when (value) {
            CatalogItemStatus.ONGOING.name -> CatalogItemStatus.ONGOING
            CatalogItemStatus.COMPLETED.name -> CatalogItemStatus.COMPLETED
            else -> error("Capability/compiler disagreement for Kitsu status '$value'")
        }
    }
}
