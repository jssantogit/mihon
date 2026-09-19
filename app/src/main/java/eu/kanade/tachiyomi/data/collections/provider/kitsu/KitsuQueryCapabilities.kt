package eu.kanade.tachiyomi.data.collections.provider.kitsu

import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.collections.capability.ProviderQueryCapabilities
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

/**
 * Capability declaration for Kitsu catalog provider.
 *
 * Kitsu remote API characteristics:
 * - Query: Supports text search via `filter[text]` (QueryField.Custom("query") or QueryField.Custom("title")).
 * - Status: Supports remote filtering ONLY for ONGOING ("current") and COMPLETED ("finished") via `filter[status]`.
 *   Cancelled, On Hiatus, Unknown are mapped to null by Kitsu provider and cannot be filtered remotely.
 * - Sorting: Supports POPULARITY_DESC, POPULARITY_ASC, RATING_DESC, RATING_ASC, UPDATED_DESC, RELEVANCE.
 * - Paging: Supports offset-based pagination.
 *
 * CRITICAL ARCHITECTURAL INVARIANT:
 * The existence of a field in CatalogQuery (e.g. genres) DOES NOT imply capability support!
 * Kitsu does NOT support remote genre filtering, score filtering, tag filtering, author filtering,
 * or chapter count filtering. These must be declared non-pushable so that they are evaluated residuals.
 */
object KitsuQueryCapabilities : ProviderQueryCapabilities {
    override val providerId: String = "kitsu"

    override val supportsOffsetPaging: Boolean = true

    override val maxPageSize: Int = 20

    override fun canPushSort(sort: CatalogSort): Boolean {
        // All values in CatalogSort are mapped in KitsuCatalogProvider
        return when (sort) {
            CatalogSort.POPULARITY_DESC,
            CatalogSort.POPULARITY_ASC,
            CatalogSort.RATING_DESC,
            CatalogSort.RATING_ASC,
            CatalogSort.UPDATED_DESC,
            CatalogSort.RELEVANCE,
            -> true
        }
    }

    override fun canPushPredicate(field: QueryField, operator: QueryOperator, value: QueryValue): Boolean {
        when (field) {
            is QueryField.Custom -> {
                if (field.identifier.equals("query", ignoreCase = true) ||
                    field.identifier.equals("title", ignoreCase = true)
                ) {
                    return (operator == QueryOperator.EQUALS || operator == QueryOperator.CONTAINS) &&
                        value is QueryValue.StringValue
                }
            }
            QueryField.Standard.STATUS -> {
                if (operator == QueryOperator.EQUALS && value is QueryValue.StringValue) {
                    val statusStr = value.value.trim().uppercase()
                    return statusStr == CatalogItemStatus.ONGOING.name ||
                        statusStr == CatalogItemStatus.COMPLETED.name ||
                        statusStr == "CURRENT" ||
                        statusStr == "FINISHED"
                }
            }
            else -> Unit
        }
        // GENRE, SCORE, TAG, AUTHOR, CHAPTER_COUNT, etc. are NOT pushable remotely on Kitsu!
        return false
    }
}

/**
 * Compiles a pushdown QueryExpression into a Kitsu-compatible CatalogQuery.
 */
object KitsuQueryCompiler {

    fun compile(
        pushdownExpression: QueryExpression?,
        sort: CatalogSort = CatalogSort.POPULARITY_DESC,
        offset: Int = 0,
        limit: Int = 20,
    ): CatalogQuery {
        var textQuery: String? = null
        var status: CatalogItemStatus? = null

        fun extractPredicate(predicate: QueryExpression.Predicate) {
            when (predicate.field) {
                is QueryField.Custom -> {
                    if (predicate.field.identifier.equals("query", ignoreCase = true) ||
                        predicate.field.identifier.equals("title", ignoreCase = true)
                    ) {
                        if (predicate.value is QueryValue.StringValue) {
                            textQuery = predicate.value.value
                        }
                    }
                }
                QueryField.Standard.STATUS -> {
                    if (predicate.value is QueryValue.StringValue) {
                        val statusStr = predicate.value.value.trim().uppercase()
                        when (statusStr) {
                            CatalogItemStatus.ONGOING.name, "CURRENT" -> status = CatalogItemStatus.ONGOING
                            CatalogItemStatus.COMPLETED.name, "FINISHED" -> status = CatalogItemStatus.COMPLETED
                        }
                    }
                }
                else -> Unit
            }
        }

        fun walk(expr: QueryExpression) {
            when (expr) {
                is QueryExpression.Predicate -> extractPredicate(expr)
                is QueryExpression.All -> expr.expressions.forEach(::walk)
                is QueryExpression.Any -> {
                    // If Any is in pushdownExpression, Kitsu doesn't support remote OR queries,
                    // so we extract nothing or treat conservatively.
                }
                is QueryExpression.Not -> {
                    // Not supported remotely by Kitsu.
                }
            }
        }

        if (pushdownExpression != null) {
            walk(pushdownExpression)
        }

        return CatalogQuery(
            query = textQuery,
            sort = sort,
            genres = emptyList(), // Kitsu does not support remote genre pushdown
            status = status,
            offset = offset,
            limit = limit,
        )
    }
}
