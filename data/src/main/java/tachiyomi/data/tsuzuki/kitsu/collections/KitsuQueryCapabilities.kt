package tachiyomi.data.tsuzuki.kitsu.collections

import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
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
 * Kitsu's manga search service supports exact filters for status, subtype and genre slugs. These
 * filters are safe to combine with AND semantics, so the Collections planner can push a conjunction
 * of those predicates while leaving unsupported predicates on the residual side.
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
        val stringValue = value as? QueryValue.StringValue ?: return false
        val normalized = stringValue.value.trim()
        if (normalized.isEmpty()) return false

        return when (field) {
            QueryField.STATUS -> {
                operator == QueryOperator.EQUALS &&
                    normalized.uppercase() in KITSU_STATUS_VALUES
            }

            QueryField.WORK_TYPE -> {
                operator == QueryOperator.EQUALS &&
                    normalized.uppercase() in KITSU_FORMAT_VALUES
            }

            QueryField.GENRE -> {
                operator in setOf(QueryOperator.EQUALS, QueryOperator.CONTAINS)
            }

            else -> false
        }
    }

    override fun canPushExpression(expression: QueryExpression): Boolean = when (expression) {
        is QueryExpression.Predicate -> canPushPredicate(
            field = expression.field,
            operator = expression.operator,
            value = expression.value,
        )

        is QueryExpression.All -> {
            val predicates = expression.expressions.map { it as? QueryExpression.Predicate ?: return false }
            if (predicates.any { !canPushExpression(it) }) return false

            val singletonFields = predicates
                .filter { it.field == QueryField.STATUS || it.field == QueryField.WORK_TYPE }
                .groupingBy { it.field }
                .eachCount()

            singletonFields.values.none { it > 1 }
        }

        is QueryExpression.Any,
        is QueryExpression.Not,
        -> false
    }

    private val KITSU_STATUS_VALUES = setOf(
        CatalogItemStatus.ONGOING.name,
        CatalogItemStatus.COMPLETED.name,
    )

    private val KITSU_FORMAT_VALUES = setOf(
        CatalogItemFormat.MANGA.name,
        CatalogItemFormat.NOVEL.name,
        CatalogItemFormat.ONE_SHOT.name,
        CatalogItemFormat.MANHWA.name,
        CatalogItemFormat.MANHUA.name,
        CatalogItemFormat.DOUJIN.name,
    )
}

/**
 * Compiles only expressions that [KitsuQueryCapabilities] declares exactly representable.
 *
 * Unsupported expressions fail closed instead of being ignored. The planner must retain those
 * expressions as residual work.
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
        if (pushdownExpression != null) {
            require(KitsuQueryCapabilities.canPushExpression(pushdownExpression)) {
                "Kitsu cannot compile Collections pushdown: ${pushdownExpression.toCanonicalString()}"
            }
        }

        var status: CatalogItemStatus? = null
        var format: CatalogItemFormat? = null
        val genres = mutableListOf<String>()

        fun collect(expression: QueryExpression) {
            when (expression) {
                is QueryExpression.Predicate -> {
                    val value = (expression.value as QueryValue.StringValue).value.trim()
                    when (expression.field) {
                        QueryField.STATUS -> {
                            status = when (value.uppercase()) {
                                CatalogItemStatus.ONGOING.name -> CatalogItemStatus.ONGOING
                                CatalogItemStatus.COMPLETED.name -> CatalogItemStatus.COMPLETED
                                else -> error("Capability/compiler disagreement for Kitsu status '$value'")
                            }
                        }

                        QueryField.WORK_TYPE -> {
                            format = when (value.uppercase()) {
                                CatalogItemFormat.MANGA.name -> CatalogItemFormat.MANGA
                                CatalogItemFormat.NOVEL.name -> CatalogItemFormat.NOVEL
                                CatalogItemFormat.ONE_SHOT.name -> CatalogItemFormat.ONE_SHOT
                                CatalogItemFormat.MANHWA.name -> CatalogItemFormat.MANHWA
                                CatalogItemFormat.MANHUA.name -> CatalogItemFormat.MANHUA
                                CatalogItemFormat.DOUJIN.name -> CatalogItemFormat.DOUJIN
                                else -> error("Capability/compiler disagreement for Kitsu work type '$value'")
                            }
                        }

                        QueryField.GENRE -> genres += value
                        else -> error("Capability/compiler disagreement for Kitsu field '${expression.field.identifier}'")
                    }
                }

                is QueryExpression.All -> expression.expressions.forEach(::collect)
                else -> error("Unsupported Kitsu pushdown expression reached compiler")
            }
        }

        pushdownExpression?.let(::collect)

        return CatalogQuery(
            query = null,
            sort = sort,
            genres = genres.distinct(),
            format = format,
            status = status,
            offset = offset,
            limit = limit,
        )
    }
}
