package eu.kanade.tachiyomi.data.tsuzuki.integration

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.data.track.myanimelist.MalIntegrationApi
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogScore
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
import tachiyomi.domain.tsuzuki.collections.capability.ResidualScanPolicy
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

object MalCollectionCapabilities : ProviderQueryCapabilities {

    private const val PROVIDER_ID = "mal"
    private const val RANKING_PAGE_SIZE = 100
    private val RANGE_OPERATORS = setOf(
        QueryOperator.EQUALS,
        QueryOperator.GREATER_OR_EQUAL,
        QueryOperator.LESS_OR_EQUAL,
        QueryOperator.BETWEEN,
    )

    override val descriptor = CollectionProviderDescriptor(
        providerId = PROVIDER_ID,
        displayName = "MyAnimeList",
        scope = CollectionProviderScope.GLOBAL,
        filters = listOf(
            residualStatic(
                id = "type",
                field = QueryField.WORK_TYPE,
                placement = FilterPlacement.QUICK,
                options = listOf(
                    option("manga", "Manga", CatalogItemFormat.MANGA.name),
                    option("one_shot", "One-shot", CatalogItemFormat.ONE_SHOT.name),
                    option("manhwa", "Manhwa", CatalogItemFormat.MANHWA.name),
                    option("manhua", "Manhua", CatalogItemFormat.MANHUA.name),
                    option("doujin", "Doujin", CatalogItemFormat.DOUJIN.name),
                ),
            ),
            residualStatic(
                id = "status",
                field = QueryField.STATUS,
                placement = FilterPlacement.QUICK,
                options = listOf(
                    option("ongoing", "Publishing", CatalogItemStatus.ONGOING.name),
                    option("completed", "Finished", CatalogItemStatus.COMPLETED.name),
                    option("hiatus", "On hiatus", CatalogItemStatus.ON_HIATUS.name),
                    option("cancelled", "Discontinued", CatalogItemStatus.CANCELLED.name),
                ),
            ),
            residualRange(
                "rating",
                QueryField.SCORE,
                FilterPlacement.QUICK,
                FilterValueSource.DecimalRange,
            ),
            residualText("genre", QueryField.GENRE),
            residualText("author", QueryField.AUTHOR),
            residualText("artist", QueryField.ARTIST),
            residualRange(
                "start_year",
                QueryField.START_YEAR,
                FilterPlacement.ADVANCED,
                FilterValueSource.IntegerRange,
            ),
            CollectionFilterCapability(
                id = "start_date",
                field = QueryField.START_DATE,
                placement = FilterPlacement.ADVANCED,
                operators = RANGE_OPERATORS,
                execution = setOf(FilterExecutionMode.RESIDUAL_EXACT),
                valueSource = FilterValueSource.DateRange,
                multiValueMode = MultiValueMode.SINGLE,
            ),
            residualRange(
                "chapters",
                QueryField.CHAPTER_COUNT,
                FilterPlacement.ADVANCED,
                FilterValueSource.IntegerRange,
            ),
            residualRange(
                "volumes",
                QueryField.VOLUME_COUNT,
                FilterPlacement.ADVANCED,
                FilterValueSource.IntegerRange,
            ),
        ),
        sorts = listOf(
            nativeSort("top", "Top"),
            nativeSort("popularity", "Popularity"),
            nativeSort("favorites", "Favorites"),
        ),
        paging = CollectionPagingCapability(
            mode = CollectionPagingMode.OFFSET,
            maxPageSize = RANKING_PAGE_SIZE,
            preferredPageSize = RANKING_PAGE_SIZE,
        ),
        scanPolicy = ResidualScanPolicy(
            maxRawItemsPerLogicalPage = 250,
            maxRemoteRequestsPerLogicalPage = 10,
            maxElapsedMillis = 10_000,
        ),
    )

    override fun canPushExpression(expression: QueryExpression): Boolean = false

    private fun residualStatic(
        id: String,
        field: QueryField,
        placement: FilterPlacement,
        options: List<FilterOption>,
    ) = CollectionFilterCapability(
        id = id,
        field = field,
        placement = placement,
        operators = setOf(QueryOperator.EQUALS),
        execution = setOf(FilterExecutionMode.RESIDUAL_EXACT),
        valueSource = FilterValueSource.Static(options),
        multiValueMode = MultiValueMode.SINGLE,
    )

    private fun residualText(id: String, field: QueryField) = CollectionFilterCapability(
        id = id,
        field = field,
        placement = FilterPlacement.ADVANCED,
        operators = setOf(QueryOperator.EQUALS, QueryOperator.CONTAINS),
        execution = setOf(FilterExecutionMode.RESIDUAL_EXACT),
        valueSource = FilterValueSource.FreeText,
        multiValueMode = MultiValueMode.SINGLE,
    )

    private fun residualRange(
        id: String,
        field: QueryField,
        placement: FilterPlacement,
        source: FilterValueSource,
    ) = CollectionFilterCapability(
        id = id,
        field = field,
        placement = placement,
        operators = RANGE_OPERATORS,
        execution = setOf(FilterExecutionMode.RESIDUAL_EXACT),
        valueSource = source,
        multiValueMode = MultiValueMode.SINGLE,
    )

    private fun option(id: String, label: String, value: String) =
        FilterOption(id, label, QueryValue.of(value))

    private fun nativeSort(id: String, label: String) = CollectionSortCapability(
        key = CollectionSortKey.Provider(PROVIDER_ID, id),
        label = label,
        directionMode = SortDirectionMode.FIXED_NATIVE,
    )

}

@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<CollectionQueryProvider>())
class MalCollectionQueryProvider private constructor(
    private val api: MalIntegrationApi,
) : CollectionQueryProvider {

    @Inject
    constructor(trackerManager: TrackerManager) : this(
        api = trackerManager.myAnimeList.integrationApi,
    )

    override val providerId = "mal"
    override val capabilities: ProviderQueryCapabilities = MalCollectionCapabilities

    override suspend fun fetch(
        pushdownExpression: QueryExpression?,
        sort: CollectionSortSelection,
        offset: Int,
        limit: Int,
    ): Result<CatalogPage> {
        require(pushdownExpression == null) {
            "MAL Collection provider does not support remote filter pushdown"
        }
        require(capabilities.canPushSort(sort)) {
            "Unsupported MAL Collection sort: $sort"
        }

        val rankingType = when ((sort.key as CollectionSortKey.Provider).nativeId) {
            "top" -> "all"
            "popularity" -> "bypopularity"
            "favorites" -> "favorite"
            else -> error("Unsupported MAL ranking stream")
        }

        return FilteredOffsetNormalizer.load(
            eligibleOffset = offset,
            limit = limit,
            upstreamPageSize = capabilities.preferredPageSize ?: 100,
            fetcher = RawOffsetCatalogFetcher { rawOffset, rawLimit ->
                runCatching {
                    val items = api.getRankingRaw(
                        rankingType = rankingType,
                        offset = rawOffset,
                        limit = rawLimit,
                    ).map(TrackSearch::toMalCollectionItem)
                    CatalogPage(
                        items = items,
                        hasNextPage = items.size == rawLimit,
                    )
                }
            },
            include = { item -> item.format != CatalogItemFormat.NOVEL },
        )
    }

    companion object {
        internal fun forTest(api: MalIntegrationApi): MalCollectionQueryProvider =
            MalCollectionQueryProvider(api)
    }
}

private fun TrackSearch.toMalCollectionItem(): CatalogItem = CatalogItem(
    provider = "mal",
    providerId = remote_id.toString(),
    title = title,
    synopsis = summary.ifBlank { null },
    coverUrl = cover_url.ifBlank { null },
    status = publishing_status.toMalCollectionStatus(),
    format = publishing_type.toMalCollectionFormat(),
    score = score
        .takeIf { it >= 0.0 }
        ?.let { value ->
            CatalogScore(
                provider = "mal",
                value = value,
                maxValue = 10.0,
                voteCount = score_votes,
            )
        },
    authors = authors.filter(String::isNotBlank).distinct(),
    artists = artists.filter(String::isNotBlank).distinct(),
    genres = genres.filter(String::isNotBlank).distinct(),
    startDate = start_date.ifBlank { null },
    endDate = end_date.ifBlank { null },
    chapterCount = total_chapters.takeIf { it > 0 && it <= Int.MAX_VALUE }?.toInt(),
    volumeCount = total_volumes.takeIf { it > 0 && it <= Int.MAX_VALUE }?.toInt(),
)

private fun String.toMalCollectionStatus(): CatalogItemStatus = when (
    lowercase().replace('_', ' ').trim()
) {
    "currently publishing", "publishing" -> CatalogItemStatus.ONGOING
    "finished" -> CatalogItemStatus.COMPLETED
    "on hiatus" -> CatalogItemStatus.ON_HIATUS
    "discontinued", "cancelled", "canceled" -> CatalogItemStatus.CANCELLED
    else -> CatalogItemStatus.UNKNOWN
}

private fun String.toMalCollectionFormat(): CatalogItemFormat = when (
    lowercase().replace('_', ' ').trim()
) {
    "manga" -> CatalogItemFormat.MANGA
    "one shot" -> CatalogItemFormat.ONE_SHOT
    "manhwa" -> CatalogItemFormat.MANHWA
    "manhua" -> CatalogItemFormat.MANHUA
    "doujin", "doujinshi" -> CatalogItemFormat.DOUJIN
    "novel", "light novel" -> CatalogItemFormat.NOVEL
    else -> CatalogItemFormat.UNKNOWN
}
