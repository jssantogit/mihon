package eu.kanade.tachiyomi.data.tsuzuki.integration

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.hikka.HikkaCollectionQuery
import eu.kanade.tachiyomi.data.track.hikka.HikkaIntegrationApi
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
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
import tachiyomi.domain.tsuzuki.collections.execution.CollectionQueryProvider
import tachiyomi.domain.tsuzuki.collections.execution.PageCatalogFetcher
import tachiyomi.domain.tsuzuki.collections.execution.PageIndexOrigin
import tachiyomi.domain.tsuzuki.collections.execution.PageOffsetNormalizer
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortDirection
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortKey
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortSelection
import tachiyomi.domain.tsuzuki.collections.model.SortDirectionMode
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

object HikkaCollectionCapabilities : ProviderQueryCapabilities {

    override val descriptor = CollectionProviderDescriptor(
        providerId = PROVIDER_ID,
        displayName = "Hikka",
        scope = CollectionProviderScope.GLOBAL,
        filters = listOf(
            CollectionFilterCapability(
                id = "type",
                field = QueryField.WORK_TYPE,
                placement = FilterPlacement.QUICK,
                operators = setOf(QueryOperator.EQUALS),
                execution = setOf(FilterExecutionMode.REMOTE_EXACT),
                valueSource = FilterValueSource.Static(
                    listOf(
                        option("manga", "Manga", CatalogItemFormat.MANGA.name),
                        option("manhwa", "Manhwa", CatalogItemFormat.MANHWA.name),
                        option("manhua", "Manhua", CatalogItemFormat.MANHUA.name),
                        option("one_shot", "One-shot", CatalogItemFormat.ONE_SHOT.name),
                        option("doujin", "Doujin", CatalogItemFormat.DOUJIN.name),
                    ),
                ),
                multiValueMode = MultiValueMode.SINGLE,
            ),
            CollectionFilterCapability(
                id = "status",
                field = QueryField.STATUS,
                placement = FilterPlacement.QUICK,
                operators = setOf(QueryOperator.EQUALS),
                execution = setOf(FilterExecutionMode.REMOTE_EXACT),
                valueSource = FilterValueSource.Static(
                    listOf(
                        option("ongoing", "Ongoing", CatalogItemStatus.ONGOING.name),
                        option("finished", "Finished", CatalogItemStatus.COMPLETED.name),
                        option("paused", "Paused", CatalogItemStatus.ON_HIATUS.name),
                        option("discontinued", "Discontinued", CatalogItemStatus.CANCELLED.name),
                    ),
                ),
                multiValueMode = MultiValueMode.SINGLE,
            ),
            CollectionFilterCapability(
                id = "year",
                field = QueryField.START_YEAR,
                placement = FilterPlacement.ADVANCED,
                operators = RANGE_OPERATORS,
                execution = setOf(FilterExecutionMode.REMOTE_EXACT),
                valueSource = FilterValueSource.IntegerRange,
                multiValueMode = MultiValueMode.SINGLE,
            ),
            CollectionFilterCapability(
                id = "genre",
                field = QueryField.GENRE,
                placement = FilterPlacement.QUICK,
                operators = setOf(QueryOperator.EQUALS),
                execution = setOf(FilterExecutionMode.REMOTE_EXACT),
                valueSource = FilterValueSource.RemoteLookup("hikka.genres"),
                multiValueMode = MultiValueMode.SINGLE,
            ),
            CollectionFilterCapability(
                id = "magazine",
                field = QueryField.Custom("hikka.magazine"),
                placement = FilterPlacement.ADVANCED,
                operators = setOf(QueryOperator.EQUALS),
                execution = setOf(FilterExecutionMode.REMOTE_EXACT),
                valueSource = FilterValueSource.FreeText,
                multiValueMode = MultiValueMode.SINGLE,
            ),
            CollectionFilterCapability(
                id = "only_translated",
                field = QueryField.Custom("hikka.only_translated"),
                placement = FilterPlacement.ADVANCED,
                operators = setOf(QueryOperator.EQUALS),
                execution = setOf(FilterExecutionMode.REMOTE_EXACT),
                valueSource = FilterValueSource.BooleanToggle,
                multiValueMode = MultiValueMode.SINGLE,
            ),
            CollectionFilterCapability(
                id = "mal_score",
                field = QueryField.Custom("hikka.mal_score"),
                placement = FilterPlacement.ADVANCED,
                operators = RANGE_OPERATORS,
                execution = setOf(FilterExecutionMode.REMOTE_EXACT),
                valueSource = FilterValueSource.DecimalRange,
                multiValueMode = MultiValueMode.SINGLE,
            ),
            CollectionFilterCapability(
                id = "hikka_score",
                field = QueryField.SCORE,
                placement = FilterPlacement.QUICK,
                operators = RANGE_OPERATORS,
                execution = setOf(FilterExecutionMode.REMOTE_EXACT),
                valueSource = FilterValueSource.DecimalRange,
                multiValueMode = MultiValueMode.SINGLE,
            ),
            CollectionFilterCapability(
                id = "query",
                field = QueryField.Custom("hikka.query"),
                placement = FilterPlacement.ADVANCED,
                operators = setOf(QueryOperator.EQUALS),
                execution = setOf(FilterExecutionMode.REMOTE_EXACT),
                valueSource = FilterValueSource.FreeText,
                multiValueMode = MultiValueMode.SINGLE,
            ),
        ),
        sorts = listOf(
            sort("mal_score", "MAL score"),
            sort("native_score", "Hikka score"),
            sort("media_type", "Media type"),
            sort("start_date", "Start date"),
            sort("created", "Created date"),
        ),
        paging = CollectionPagingCapability(
            mode = CollectionPagingMode.PAGE,
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
        is QueryExpression.All -> {
            expression.expressions.all(::canPushExpression) &&
                expression.expressions
                    .mapNotNull { it as? QueryExpression.Predicate }
                    .groupingBy { it.field }
                    .eachCount()
                    .values
                    .none { it > 1 }
        }
        is QueryExpression.Any,
        is QueryExpression.Not,
        -> false
    }

    private fun option(id: String, label: String, value: String) =
        FilterOption(id, label, QueryValue.of(value))

    private fun sort(id: String, label: String) = CollectionSortCapability(
        key = CollectionSortKey.Provider(PROVIDER_ID, id),
        label = label,
        directionMode = SortDirectionMode.ASC_DESC,
        defaultDirection = CollectionSortDirection.DESC,
    )

    private const val PROVIDER_ID = "hikka"
    private const val PAGE_SIZE = 50
    private val RANGE_OPERATORS = setOf(
        QueryOperator.GREATER_OR_EQUAL,
        QueryOperator.LESS_OR_EQUAL,
        QueryOperator.BETWEEN,
    )
}

object HikkaCollectionCompiler {

    fun compile(
        expression: QueryExpression?,
        sort: CollectionSortSelection,
    ): HikkaCollectionQuery {
        require(HikkaCollectionCapabilities.canPushSort(sort)) {
            "Unsupported Hikka Collection sort: $sort"
        }
        if (expression != null) {
            require(HikkaCollectionCapabilities.canPushExpression(expression)) {
                "Unsupported Hikka Collection pushdown: ${expression.toCanonicalString()}"
            }
        }

        var yearFrom: Int? = null
        var yearTo: Int? = null
        var mediaType: String? = null
        var status: String? = null
        var onlyTranslated: Boolean? = null
        var magazine: String? = null
        var genre: String? = null
        var malScoreFrom: Double? = null
        var malScoreTo: Double? = null
        var nativeScoreFrom: Double? = null
        var nativeScoreTo: Double? = null
        var queryText: String? = null

        fun collect(predicate: QueryExpression.Predicate) {
            when (predicate.field) {
                QueryField.START_YEAR -> {
                    val bounds = predicate.value.numericBounds(predicate.operator)
                    yearFrom = bounds.first?.toInt()
                    yearTo = bounds.second?.toInt()
                }
                QueryField.WORK_TYPE -> {
                    mediaType = when ((predicate.value as QueryValue.StringValue).value.uppercase()) {
                        CatalogItemFormat.MANGA.name -> "manga"
                        CatalogItemFormat.MANHWA.name -> "manhwa"
                        CatalogItemFormat.MANHUA.name -> "manhua"
                        CatalogItemFormat.ONE_SHOT.name -> "one_shot"
                        CatalogItemFormat.DOUJIN.name -> "doujin"
                        else -> error("Unsupported Hikka work type")
                    }
                }
                QueryField.STATUS -> {
                    status = when ((predicate.value as QueryValue.StringValue).value.uppercase()) {
                        CatalogItemStatus.ONGOING.name -> "ongoing"
                        CatalogItemStatus.COMPLETED.name -> "finished"
                        CatalogItemStatus.ON_HIATUS.name -> "paused"
                        CatalogItemStatus.CANCELLED.name -> "discontinued"
                        else -> error("Unsupported Hikka status")
                    }
                }
                QueryField.GENRE -> genre = (predicate.value as QueryValue.StringValue).value
                QueryField.Custom("hikka.magazine") ->
                    magazine = (predicate.value as QueryValue.StringValue).value
                QueryField.Custom("hikka.only_translated") ->
                    onlyTranslated = (predicate.value as QueryValue.BooleanValue).value
                QueryField.Custom("hikka.mal_score") -> {
                    val bounds = predicate.value.numericBounds(predicate.operator)
                    malScoreFrom = bounds.first
                    malScoreTo = bounds.second
                }
                QueryField.SCORE -> {
                    val bounds = predicate.value.numericBounds(predicate.operator)
                    nativeScoreFrom = bounds.first
                    nativeScoreTo = bounds.second
                }
                QueryField.Custom("hikka.query") ->
                    queryText = (predicate.value as QueryValue.StringValue).value
                else -> error("Capability/compiler disagreement for ${predicate.field.identifier}")
            }
        }

        when (expression) {
            null -> Unit
            is QueryExpression.Predicate -> collect(expression)
            is QueryExpression.All -> expression.expressions.forEach { collect(it as QueryExpression.Predicate) }
            else -> error("Unsupported Hikka expression reached compiler")
        }

        val key = sort.key as CollectionSortKey.Provider
        val direction = requireNotNull(sort.direction).name.lowercase()
        return HikkaCollectionQuery(
            yearFrom = yearFrom,
            yearTo = yearTo,
            mediaTypes = listOfNotNull(mediaType),
            statuses = listOfNotNull(status),
            onlyTranslated = onlyTranslated,
            magazines = listOfNotNull(magazine),
            genres = listOfNotNull(genre),
            malScoreFrom = malScoreFrom,
            malScoreTo = malScoreTo,
            nativeScoreFrom = nativeScoreFrom,
            nativeScoreTo = nativeScoreTo,
            query = queryText,
            sort = "${key.nativeId}:$direction",
            page = 1,
            size = 50,
        )
    }

    private fun QueryValue.numericBounds(operator: QueryOperator): Pair<Double?, Double?> = when (operator) {
        QueryOperator.BETWEEN -> {
            val range = this as QueryValue.RangeValue
            range.lower.number() to range.upper.number()
        }
        QueryOperator.GREATER_OR_EQUAL -> number() to null
        QueryOperator.LESS_OR_EQUAL -> null to number()
        else -> error("Unsupported Hikka range operator: $operator")
    }

    private fun QueryValue.number(): Double = when (this) {
        is QueryValue.IntegerValue -> value.toDouble()
        is QueryValue.DoubleValue -> value
        else -> error("Expected numeric Hikka query value")
    }
}

@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<CollectionQueryProvider>())
class HikkaCollectionQueryProvider private constructor(
    private val api: HikkaIntegrationApi,
) : CollectionQueryProvider {

    @Inject
    constructor(trackerManager: TrackerManager) : this(
        api = trackerManager.hikka.integrationApi,
    )

    override val providerId = "hikka"
    override val capabilities: ProviderQueryCapabilities = HikkaCollectionCapabilities

    override suspend fun fetch(
        pushdownExpression: QueryExpression?,
        sort: CollectionSortSelection,
        offset: Int,
        limit: Int,
    ): Result<CatalogPage> {
        val compiled = HikkaCollectionCompiler.compile(pushdownExpression, sort)
        return PageOffsetNormalizer.load(
            rawOffset = offset,
            limit = limit,
            upstreamPageSize = capabilities.preferredPageSize ?: 50,
            pageOrigin = PageIndexOrigin.ONE,
            fetcher = PageCatalogFetcher { page, pageSize ->
                runCatching {
                    val response = api.collectionSearch(
                        compiled.copy(page = page, size = pageSize),
                    )
                    CatalogPage(
                        items = response.items.map { manga ->
                            manga.toTrack(10L)
                                .toIntegrationCatalogItem(providerId)
                                .copy(
                                    providerId = manga.slug,
                                    externalIds = manga.malId
                                        ?.let { mapOf("mal" to it.toString()) }
                                        .orEmpty(),
                                )
                        },
                        hasNextPage = response.page < response.pages,
                        totalCount = response.total,
                    )
                }
            },
        )
    }

    companion object {
        internal fun forTest(api: HikkaIntegrationApi): HikkaCollectionQueryProvider =
            HikkaCollectionQueryProvider(api)
    }
}
