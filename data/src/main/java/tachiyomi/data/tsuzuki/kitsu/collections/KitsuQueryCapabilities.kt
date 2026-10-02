package tachiyomi.data.tsuzuki.kitsu.collections

import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.collections.capability.CollectionFilterCapability
import tachiyomi.domain.tsuzuki.collections.capability.CollectionPagingCapability
import tachiyomi.domain.tsuzuki.collections.capability.CollectionPagingMode
import tachiyomi.domain.tsuzuki.collections.capability.CollectionProviderDescriptor
import tachiyomi.domain.tsuzuki.collections.capability.CollectionProviderScope
import tachiyomi.domain.tsuzuki.collections.capability.CollectionSortCapability
import tachiyomi.domain.tsuzuki.collections.capability.FilterExecutionMode
import tachiyomi.domain.tsuzuki.collections.capability.FilterOption
import tachiyomi.domain.tsuzuki.collections.capability.FilterPlacement
import tachiyomi.domain.tsuzuki.collections.capability.FilterValueSource
import tachiyomi.domain.tsuzuki.collections.capability.MultiValueMode
import tachiyomi.domain.tsuzuki.collections.capability.ProviderQueryCapabilities
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortDirection
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortKey
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortSelection
import tachiyomi.domain.tsuzuki.collections.model.SortDirectionMode
import tachiyomi.domain.tsuzuki.collections.model.toLegacyCatalogSortOrNull
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

/**
 * Exact Collections-query capabilities for the Kitsu catalog provider.
 *
 * Kitsu's current Tsuzuki adapter pushes status, subtype and positive genre filters exactly. The
 * descriptor is also the source of truth for which of those controls may be presented by a
 * capability-driven List Builder.
 */
object KitsuQueryCapabilities : ProviderQueryCapabilities {

    override val descriptor: CollectionProviderDescriptor = CollectionProviderDescriptor(
        providerId = "kitsu",
        displayName = "Kitsu",
        scope = CollectionProviderScope.GLOBAL,
        filters = listOf(
            CollectionFilterCapability(
                id = "status",
                field = QueryField.STATUS,
                placement = FilterPlacement.QUICK,
                operators = setOf(QueryOperator.EQUALS),
                execution = setOf(FilterExecutionMode.REMOTE_EXACT),
                valueSource = FilterValueSource.Static(
                    listOf(
                        FilterOption("ongoing", "Ongoing", QueryValue.of(CatalogItemStatus.ONGOING.name)),
                        FilterOption("completed", "Completed", QueryValue.of(CatalogItemStatus.COMPLETED.name)),
                    ),
                ),
                multiValueMode = MultiValueMode.SINGLE,
            ),
            CollectionFilterCapability(
                id = "type",
                field = QueryField.WORK_TYPE,
                placement = FilterPlacement.QUICK,
                operators = setOf(QueryOperator.EQUALS),
                execution = setOf(FilterExecutionMode.REMOTE_EXACT),
                valueSource = FilterValueSource.Static(
                    KITSU_FORMAT_VALUES.map { value ->
                        FilterOption(
                            id = value.lowercase(),
                            label = value.replace('_', ' ').lowercase()
                                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                            value = QueryValue.of(value),
                        )
                    },
                ),
                multiValueMode = MultiValueMode.SINGLE,
            ),
            CollectionFilterCapability(
                id = "genre",
                field = QueryField.GENRE,
                placement = FilterPlacement.QUICK,
                operators = setOf(QueryOperator.EQUALS, QueryOperator.CONTAINS),
                execution = setOf(FilterExecutionMode.REMOTE_EXACT),
                valueSource = FilterValueSource.RemoteLookup("kitsu.genres"),
                multiValueMode = MultiValueMode.ALL,
            ),
            CollectionFilterCapability(
                id = "rating",
                field = QueryField.SCORE,
                placement = FilterPlacement.QUICK,
                operators = setOf(
                    QueryOperator.GREATER_OR_EQUAL,
                    QueryOperator.LESS_OR_EQUAL,
                    QueryOperator.BETWEEN,
                ),
                execution = setOf(FilterExecutionMode.RESIDUAL_EXACT),
                valueSource = FilterValueSource.DecimalRange,
                multiValueMode = MultiValueMode.SINGLE,
            ),
            CollectionFilterCapability(
                id = "start_year",
                field = QueryField.START_YEAR,
                placement = FilterPlacement.ADVANCED,
                operators = setOf(
                    QueryOperator.GREATER_OR_EQUAL,
                    QueryOperator.LESS_OR_EQUAL,
                    QueryOperator.BETWEEN,
                ),
                execution = setOf(FilterExecutionMode.RESIDUAL_EXACT),
                valueSource = FilterValueSource.IntegerRange,
                multiValueMode = MultiValueMode.SINGLE,
            ),
            CollectionFilterCapability(
                id = "chapter_count",
                field = QueryField.CHAPTER_COUNT,
                placement = FilterPlacement.ADVANCED,
                operators = setOf(
                    QueryOperator.GREATER_OR_EQUAL,
                    QueryOperator.LESS_OR_EQUAL,
                    QueryOperator.BETWEEN,
                ),
                execution = setOf(FilterExecutionMode.RESIDUAL_EXACT),
                valueSource = FilterValueSource.IntegerRange,
                multiValueMode = MultiValueMode.SINGLE,
            ),
            CollectionFilterCapability(
                id = "volume_count",
                field = QueryField.VOLUME_COUNT,
                placement = FilterPlacement.ADVANCED,
                operators = setOf(
                    QueryOperator.GREATER_OR_EQUAL,
                    QueryOperator.LESS_OR_EQUAL,
                    QueryOperator.BETWEEN,
                ),
                execution = setOf(FilterExecutionMode.RESIDUAL_EXACT),
                valueSource = FilterValueSource.IntegerRange,
                multiValueMode = MultiValueMode.SINGLE,
            ),
            CollectionFilterCapability(
                id = "popularity",
                field = QueryField.POPULARITY,
                placement = FilterPlacement.ADVANCED,
                operators = setOf(
                    QueryOperator.GREATER_OR_EQUAL,
                    QueryOperator.LESS_OR_EQUAL,
                    QueryOperator.BETWEEN,
                ),
                execution = setOf(FilterExecutionMode.RESIDUAL_EXACT),
                valueSource = FilterValueSource.IntegerRange,
                multiValueMode = MultiValueMode.SINGLE,
            ),
        ),
        sorts = listOf(
            CollectionSortCapability(
                key = CollectionSortKey.Standard.POPULARITY,
                label = "Popularity",
                directionMode = SortDirectionMode.ASC_DESC,
                defaultDirection = CollectionSortDirection.DESC,
            ),
            CollectionSortCapability(
                key = CollectionSortKey.Standard.RATING,
                label = "Rating",
                directionMode = SortDirectionMode.ASC_DESC,
                defaultDirection = CollectionSortDirection.DESC,
            ),
            CollectionSortCapability(
                key = CollectionSortKey.Standard.UPDATED,
                label = "Recently Updated",
                directionMode = SortDirectionMode.DESC_ONLY,
                defaultDirection = CollectionSortDirection.DESC,
            ),
        ),
        paging = CollectionPagingCapability(
            mode = CollectionPagingMode.OFFSET,
            maxPageSize = 20,
            preferredPageSize = 20,
        ),
    )

    override fun canPushExpression(expression: QueryExpression): Boolean = when (expression) {
        is QueryExpression.Predicate -> canPushPredicate(
            field = expression.field,
            operator = expression.operator,
            value = expression.value,
        )

        is QueryExpression.All -> {
            val predicates = expression.expressions.mapNotNull { it as? QueryExpression.Predicate }
            if (predicates.size != expression.expressions.size || predicates.any { !canPushExpression(it) }) {
                false
            } else {
                val singletonFields = predicates
                    .filter { it.field == QueryField.STATUS || it.field == QueryField.WORK_TYPE }
                    .groupingBy { it.field }
                    .eachCount()

                singletonFields.values.none { it > 1 }
            }
        }

        is QueryExpression.Any,
        is QueryExpression.Not,
        -> false
    }

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
 * Compiles only expressions and sorts that [KitsuQueryCapabilities] declares exactly representable.
 *
 * Unsupported expressions fail closed instead of being ignored. The planner must retain those
 * expressions as residual work.
 */
object KitsuQueryCompiler {

    fun compile(
        pushdownExpression: QueryExpression?,
        sort: CollectionSortSelection = CollectionSortSelection.DEFAULT,
        offset: Int = 0,
        limit: Int = 20,
    ): CatalogQuery {
        require(offset >= 0) { "Kitsu offset must be non-negative" }
        require(limit in 1..KitsuQueryCapabilities.maxPageSize!!) {
            "Kitsu page limit must be between 1 and ${KitsuQueryCapabilities.maxPageSize}"
        }
        require(KitsuQueryCapabilities.canPushSort(sort)) {
            "Kitsu cannot guarantee exact remote ordering for $sort"
        }
        val catalogSort = requireNotNull(sort.toLegacyCatalogSortOrNull()) {
            "Kitsu Collection sort has no legacy catalog mapping: $sort"
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
                        else -> error(
                            "Capability/compiler disagreement for Kitsu field '${expression.field.identifier}'",
                        )
                    }
                }

                is QueryExpression.All -> expression.expressions.forEach(::collect)
                else -> error("Unsupported Kitsu pushdown expression reached compiler")
            }
        }

        pushdownExpression?.let(::collect)

        return CatalogQuery(
            query = null,
            sort = catalogSort,
            genres = genres.distinct(),
            format = format,
            status = status,
            offset = offset,
            limit = limit,
        )
    }
}
