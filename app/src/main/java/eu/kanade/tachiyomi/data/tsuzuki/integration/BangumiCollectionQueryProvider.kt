package eu.kanade.tachiyomi.data.tsuzuki.integration

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.bangumi.BangumiCollectionQuery
import eu.kanade.tachiyomi.data.track.bangumi.BangumiIntegrationApi
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.collections.capability.CollectionFilterCapability
import tachiyomi.domain.tsuzuki.collections.capability.CollectionPagingCapability
import tachiyomi.domain.tsuzuki.collections.capability.CollectionPagingMode
import tachiyomi.domain.tsuzuki.collections.capability.CollectionProviderDescriptor
import tachiyomi.domain.tsuzuki.collections.capability.CollectionProviderScope
import tachiyomi.domain.tsuzuki.collections.capability.CollectionSortCapability
import tachiyomi.domain.tsuzuki.collections.capability.FilterExecutionMode
import tachiyomi.domain.tsuzuki.collections.capability.FilterPlacement
import tachiyomi.domain.tsuzuki.collections.capability.FilterValueSource
import tachiyomi.domain.tsuzuki.collections.capability.MultiValueMode
import tachiyomi.domain.tsuzuki.collections.capability.ProviderQueryCapabilities
import tachiyomi.domain.tsuzuki.collections.execution.CollectionQueryProvider
import tachiyomi.domain.tsuzuki.collections.execution.FilteredOffsetNormalizer
import tachiyomi.domain.tsuzuki.collections.execution.RawOffsetCatalogFetcher
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortKey
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortSelection
import tachiyomi.domain.tsuzuki.collections.model.SortDirectionMode
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

object BangumiCollectionCapabilities : ProviderQueryCapabilities {

    private const val PROVIDER_ID = "bangumi"
    private const val PAGE_SIZE = 50
    private val RANGE_OPERATORS = setOf(
        QueryOperator.GREATER_THAN,
        QueryOperator.GREATER_OR_EQUAL,
        QueryOperator.LESS_THAN,
        QueryOperator.LESS_OR_EQUAL,
        QueryOperator.BETWEEN,
    )

    override val descriptor = CollectionProviderDescriptor(
        providerId = PROVIDER_ID,
        displayName = "Bangumi",
        scope = CollectionProviderScope.GLOBAL,
        filters = listOf(
            textFilter("tag", QueryField.TAG, FilterValueSource.FreeText),
            textFilter(
                "meta_tag",
                QueryField.Custom("bangumi.meta_tag"),
                FilterValueSource.FreeText,
                multiValueMode = MultiValueMode.INCLUDE_EXCLUDE,
            ),
            rangeFilter("release_date", QueryField.START_DATE, FilterValueSource.DateRange),
            rangeFilter("rating", QueryField.SCORE, FilterValueSource.DecimalRange),
            rangeFilter(
                "rating_count",
                QueryField.Custom("bangumi.rating_count"),
                FilterValueSource.IntegerRange,
            ),
            rangeFilter("rank", QueryField.RANK, FilterValueSource.IntegerRange),
            CollectionFilterCapability(
                id = "nsfw",
                field = QueryField.Custom("bangumi.nsfw"),
                placement = FilterPlacement.ADVANCED,
                operators = setOf(QueryOperator.EQUALS),
                execution = setOf(FilterExecutionMode.REMOTE_EXACT),
                valueSource = FilterValueSource.BooleanToggle,
                multiValueMode = MultiValueMode.SINGLE,
            ),
            textFilter(
                "query",
                QueryField.Custom("bangumi.query"),
                FilterValueSource.FreeText,
            ),
        ),
        sorts = listOf(
            nativeSort("match", "Match"),
            nativeSort("heat", "Heat"),
            nativeSort("rank", "Rank"),
            nativeSort("score", "Score"),
        ),
        paging = CollectionPagingCapability(
            mode = CollectionPagingMode.OFFSET,
            maxPageSize = PAGE_SIZE,
            preferredPageSize = PAGE_SIZE,
        ),
    )

    override fun canPushExpression(expression: QueryExpression): Boolean = when (expression) {
        is QueryExpression.Predicate -> canPushPredicate(
            expression.field,
            expression.operator,
            expression.value,
        )
        is QueryExpression.Not -> {
            val predicate = expression.expression as? QueryExpression.Predicate
            predicate?.field == QueryField.Custom("bangumi.meta_tag") &&
                canPushPredicate(predicate.field, predicate.operator, predicate.value)
        }
        is QueryExpression.All -> expression.expressions.all(::canPushExpression)
        is QueryExpression.Any -> false
    }

    private fun textFilter(
        id: String,
        field: QueryField,
        source: FilterValueSource,
        multiValueMode: MultiValueMode = MultiValueMode.SINGLE,
    ) = CollectionFilterCapability(
        id = id,
        field = field,
        placement = FilterPlacement.ADVANCED,
        operators = setOf(QueryOperator.EQUALS),
        execution = setOf(FilterExecutionMode.REMOTE_EXACT),
        valueSource = source,
        multiValueMode = multiValueMode,
    )

    private fun rangeFilter(
        id: String,
        field: QueryField,
        source: FilterValueSource,
    ) = CollectionFilterCapability(
        id = id,
        field = field,
        placement = FilterPlacement.ADVANCED,
        operators = RANGE_OPERATORS,
        execution = setOf(FilterExecutionMode.REMOTE_EXACT),
        valueSource = source,
        multiValueMode = MultiValueMode.SINGLE,
    )

    private fun nativeSort(id: String, label: String) = CollectionSortCapability(
        key = CollectionSortKey.Provider(PROVIDER_ID, id),
        label = label,
        directionMode = SortDirectionMode.FIXED_NATIVE,
    )

}

object BangumiCollectionCompiler {

    fun compile(
        expression: QueryExpression?,
        sort: CollectionSortSelection,
    ): BangumiCollectionQuery {
        require(BangumiCollectionCapabilities.canPushSort(sort)) {
            "Unsupported Bangumi Collection sort: $sort"
        }
        if (expression != null) {
            require(BangumiCollectionCapabilities.canPushExpression(expression)) {
                "Unsupported Bangumi Collection pushdown: ${expression.toCanonicalString()}"
            }
        }

        val tags = mutableListOf<String>()
        val metaTags = mutableListOf<String>()
        val airDate = mutableListOf<String>()
        val rating = mutableListOf<String>()
        val ratingCount = mutableListOf<String>()
        val rank = mutableListOf<String>()
        var nsfw: Boolean? = null
        var keyword = ""

        fun collect(term: QueryExpression, negative: Boolean = false) {
            when (term) {
                is QueryExpression.Predicate -> when (term.field) {
                    QueryField.TAG -> tags += (term.value as QueryValue.StringValue).value
                    QueryField.Custom("bangumi.meta_tag") -> {
                        val value = (term.value as QueryValue.StringValue).value
                        metaTags += if (negative) "-$value" else value
                    }
                    QueryField.START_DATE -> airDate += term.comparisonStrings()
                    QueryField.SCORE -> rating += term.comparisonStrings()
                    QueryField.Custom("bangumi.rating_count") -> ratingCount += term.comparisonStrings()
                    QueryField.RANK -> rank += term.comparisonStrings()
                    QueryField.Custom("bangumi.nsfw") ->
                        nsfw = (term.value as QueryValue.BooleanValue).value
                    QueryField.Custom("bangumi.query") ->
                        keyword = (term.value as QueryValue.StringValue).value
                    else -> error("Capability/compiler disagreement for ${term.field.identifier}")
                }
                is QueryExpression.Not -> collect(term.expression, negative = true)
                is QueryExpression.All -> term.expressions.forEach { child -> collect(child) }
                is QueryExpression.Any -> error("ANY cannot reach Bangumi compiler")
            }
        }

        expression?.let(::collect)
        val key = sort.key as CollectionSortKey.Provider
        return BangumiCollectionQuery(
            keyword = keyword,
            sort = key.nativeId,
            tags = tags,
            metaTags = metaTags,
            airDate = airDate,
            rating = rating,
            ratingCount = ratingCount,
            rank = rank,
            nsfw = nsfw,
            offset = 0,
            limit = 50,
        )
    }

    private fun QueryExpression.Predicate.comparisonStrings(): List<String> = when (operator) {
        QueryOperator.GREATER_THAN -> listOf(">${value.scalarText()}")
        QueryOperator.GREATER_OR_EQUAL -> listOf(">=${value.scalarText()}")
        QueryOperator.LESS_THAN -> listOf("<${value.scalarText()}")
        QueryOperator.LESS_OR_EQUAL -> listOf("<=${value.scalarText()}")
        QueryOperator.BETWEEN -> {
            val range = value as QueryValue.RangeValue
            listOf(">=${range.lower.scalarText()}", "<=${range.upper.scalarText()}")
        }
        else -> error("Unsupported Bangumi comparison operator: $operator")
    }

    private fun QueryValue.scalarText(): String = when (this) {
        is QueryValue.StringValue -> value
        is QueryValue.IntegerValue -> value.toString()
        is QueryValue.DoubleValue -> value.toString()
        else -> error("Expected scalar Bangumi query value")
    }
}

@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<CollectionQueryProvider>())
class BangumiCollectionQueryProvider private constructor(
    private val api: BangumiIntegrationApi,
) : CollectionQueryProvider {

    @Inject
    constructor(trackerManager: TrackerManager) : this(
        api = trackerManager.bangumi.integrationApi,
    )

    override val providerId = "bangumi"
    override val capabilities: ProviderQueryCapabilities = BangumiCollectionCapabilities

    override suspend fun fetch(
        pushdownExpression: QueryExpression?,
        sort: CollectionSortSelection,
        offset: Int,
        limit: Int,
    ): Result<CatalogPage> {
        val compiled = BangumiCollectionCompiler.compile(pushdownExpression, sort)
        return FilteredOffsetNormalizer.load(
            eligibleOffset = offset,
            limit = limit,
            upstreamPageSize = capabilities.preferredPageSize ?: 50,
            fetcher = RawOffsetCatalogFetcher { rawOffset, rawLimit ->
                runCatching {
                    val response = api.collectionSearch(
                        compiled.copy(offset = rawOffset, limit = rawLimit),
                    )
                    val items = response.items.map { it.toIntegrationCatalogItem(providerId) }
                    CatalogPage(
                        items = items,
                        hasNextPage = rawOffset + items.size < response.total,
                        totalCount = response.total,
                    )
                }
            },
            include = { item -> item.format == CatalogItemFormat.MANGA },
        )
    }

    companion object {
        internal fun forTest(api: BangumiIntegrationApi): BangumiCollectionQueryProvider =
            BangumiCollectionQueryProvider(api)
    }
}
