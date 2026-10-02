package eu.kanade.tachiyomi.data.tsuzuki.integration

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdatesCollectionQuery
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdatesIntegrationApi
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
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
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortKey
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortSelection
import tachiyomi.domain.tsuzuki.collections.model.SortDirectionMode
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

object MangaUpdatesCollectionCapabilities : ProviderQueryCapabilities {

    override val descriptor = CollectionProviderDescriptor(
        providerId = PROVIDER_ID,
        displayName = "MangaUpdates",
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
                        formatOption(CatalogItemFormat.MANGA, "Manga"),
                        formatOption(CatalogItemFormat.MANHWA, "Manhwa"),
                        formatOption(CatalogItemFormat.MANHUA, "Manhua"),
                        formatOption(CatalogItemFormat.DOUJIN, "Doujinshi"),
                        formatOption(CatalogItemFormat.NOVEL, "Novel"),
                    ),
                ),
                multiValueMode = MultiValueMode.SINGLE,
            ),
            CollectionFilterCapability(
                id = "genre",
                field = QueryField.GENRE,
                placement = FilterPlacement.ADVANCED,
                operators = setOf(QueryOperator.EQUALS, QueryOperator.CONTAINS),
                execution = setOf(FilterExecutionMode.REMOTE_EXACT),
                valueSource = FilterValueSource.RemoteLookup("mangaupdates.genres"),
                multiValueMode = MultiValueMode.INCLUDE_EXCLUDE,
            ),
            CollectionFilterCapability(
                id = "category",
                field = QueryField.CATEGORY,
                placement = FilterPlacement.ADVANCED,
                operators = setOf(QueryOperator.EQUALS),
                execution = setOf(FilterExecutionMode.REMOTE_EXACT),
                valueSource = FilterValueSource.RemoteLookup("mangaupdates.categories"),
                multiValueMode = MultiValueMode.SINGLE,
            ),
            CollectionFilterCapability(
                id = "licensed",
                field = QueryField.Custom("mangaupdates.licensed"),
                placement = FilterPlacement.ADVANCED,
                operators = setOf(QueryOperator.EQUALS),
                execution = setOf(FilterExecutionMode.REMOTE_EXACT),
                valueSource = FilterValueSource.BooleanToggle,
                multiValueMode = MultiValueMode.SINGLE,
            ),
            CollectionFilterCapability(
                id = "release_filter",
                field = QueryField.Custom("mangaupdates.release_filter"),
                placement = FilterPlacement.ADVANCED,
                operators = setOf(QueryOperator.EQUALS),
                execution = setOf(FilterExecutionMode.REMOTE_EXACT),
                valueSource = FilterValueSource.Static(
                    MANGA_UPDATES_RELEASE_FILTERS.map { value ->
                        FilterOption(
                            value,
                            value.replace('_', ' ').replaceFirstChar { it.titlecase() },
                            QueryValue.of(value),
                        )
                    },
                ),
                multiValueMode = MultiValueMode.SINGLE,
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
        ),
        sorts = MANGA_UPDATES_SORTS.map { (nativeId, label) ->
            CollectionSortCapability(
                key = CollectionSortKey.Provider(PROVIDER_ID, nativeId),
                label = label,
                directionMode = SortDirectionMode.FIXED_NATIVE,
            )
        },
        paging = CollectionPagingCapability(
            mode = CollectionPagingMode.PAGE,
            maxPageSize = MANGA_UPDATES_PAGE_SIZE,
            preferredPageSize = MANGA_UPDATES_PAGE_SIZE,
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
            predicate?.field == QueryField.GENRE &&
                canPushPredicate(predicate.field, predicate.operator, predicate.value)
        }
        is QueryExpression.All -> {
            if (expression.expressions.any { !canPushExpression(it) }) {
                false
            } else {
                val singletonCounts = expression.expressions
                    .mapNotNull { candidate ->
                        when (candidate) {
                            is QueryExpression.Predicate -> candidate.field
                            is QueryExpression.Not -> null
                            else -> null
                        }
                    }
                    .filter { it != QueryField.GENRE }
                    .groupingBy { it }
                    .eachCount()
                val positiveGenres = expression.expressions.count {
                    it is QueryExpression.Predicate && it.field == QueryField.GENRE
                }
                val negativeGenres = expression.expressions.count {
                    it is QueryExpression.Not &&
                        (it.expression as? QueryExpression.Predicate)?.field == QueryField.GENRE
                }
                singletonCounts.values.none { it > 1 } && positiveGenres <= 1 && negativeGenres <= 1
            }
        }
        is QueryExpression.Any -> false
    }

    private fun formatOption(
        format: CatalogItemFormat,
        label: String,
    ) = FilterOption(
        id = format.name.lowercase(),
        label = label,
        value = QueryValue.of(format.name),
    )

    private const val PROVIDER_ID = "mangaupdates"
    private const val MANGA_UPDATES_PAGE_SIZE = 50

    private val MANGA_UPDATES_RELEASE_FILTERS = listOf(
        "scanlated",
        "completed",
        "oneshots",
        "no_oneshots",
        "some_releases",
        "no_releases",
    )

    private val MANGA_UPDATES_SORTS = listOf(
        "score" to "Score",
        "title" to "Title",
        "rank" to "Rank",
        "rating" to "Rating",
        "year" to "Year",
        "date_added" to "Date added",
        "week_pos" to "Weekly popularity",
        "month1_pos" to "Monthly popularity",
        "month3_pos" to "3-month popularity",
        "month6_pos" to "6-month popularity",
        "year_pos" to "Yearly popularity",
        "list_reading" to "Reading-list popularity",
        "list_wish" to "Wish-list popularity",
        "list_complete" to "Complete-list popularity",
        "list_unfinished" to "Unfinished-list popularity",
    )
}

object MangaUpdatesCollectionCompiler {

    fun compile(
        pushdownExpression: QueryExpression?,
        sort: CollectionSortSelection,
    ): MangaUpdatesCollectionQuery {
        require(MangaUpdatesCollectionCapabilities.canPushSort(sort)) {
            "Unsupported MangaUpdates Collection sort: $sort"
        }
        if (pushdownExpression != null) {
            require(MangaUpdatesCollectionCapabilities.canPushExpression(pushdownExpression)) {
                "Unsupported MangaUpdates Collection pushdown: ${pushdownExpression.toCanonicalString()}"
            }
        }

        var type: String? = null
        var category: String? = null
        var licensed: Boolean? = null
        var releaseFilter: String? = null
        var genre: String? = null
        var excludeGenre: String? = null

        fun collect(expression: QueryExpression) {
            when (expression) {
                is QueryExpression.Predicate -> {
                    when (expression.field) {
                        QueryField.WORK_TYPE -> {
                            val value = (expression.value as QueryValue.StringValue).value
                            type = when (value.uppercase()) {
                                CatalogItemFormat.MANGA.name -> "Manga"
                                CatalogItemFormat.MANHWA.name -> "Manhwa"
                                CatalogItemFormat.MANHUA.name -> "Manhua"
                                CatalogItemFormat.DOUJIN.name -> "Doujinshi"
                                CatalogItemFormat.NOVEL.name -> "Novel"
                                else -> error("Unsupported MangaUpdates work type '$value'")
                            }
                        }
                        QueryField.GENRE -> {
                            genre = (expression.value as QueryValue.StringValue).value
                        }
                        QueryField.CATEGORY -> {
                            category = (expression.value as QueryValue.StringValue).value
                        }
                        QueryField.Custom("mangaupdates.licensed") -> {
                            licensed = (expression.value as QueryValue.BooleanValue).value
                        }
                        QueryField.Custom("mangaupdates.release_filter") -> {
                            releaseFilter = (expression.value as QueryValue.StringValue).value
                        }
                        else -> error(
                            "Capability/compiler disagreement for ${expression.field.identifier}",
                        )
                    }
                }
                is QueryExpression.Not -> {
                    val predicate = expression.expression as QueryExpression.Predicate
                    excludeGenre = (predicate.value as QueryValue.StringValue).value
                }
                is QueryExpression.All -> expression.expressions.forEach(::collect)
                is QueryExpression.Any -> error("ANY cannot reach MangaUpdates compiler")
            }
        }

        pushdownExpression?.let(::collect)
        val key = sort.key as CollectionSortKey.Provider
        return MangaUpdatesCollectionQuery(
            type = type,
            category = category,
            licensed = licensed,
            releaseFilter = releaseFilter,
            genre = genre,
            excludeGenre = excludeGenre,
            orderBy = key.nativeId,
            page = 1,
            perPage = 50,
        )
    }
}

@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<CollectionQueryProvider>())
class MangaUpdatesCollectionQueryProvider private constructor(
    private val api: MangaUpdatesIntegrationApi,
) : CollectionQueryProvider {

    @Inject
    constructor(trackerManager: TrackerManager) : this(
        api = trackerManager.mangaUpdates.integrationApi,
    )

    override val providerId: String = "mangaupdates"
    override val capabilities: ProviderQueryCapabilities = MangaUpdatesCollectionCapabilities

    override suspend fun lookupValues(
        lookupId: String,
        query: String?,
    ): Result<List<FilterOption>> {
        val values = when (lookupId) {
            "mangaupdates.genres" -> api.lookupGenres()
            "mangaupdates.categories" -> api.lookupCategories(query.orEmpty())
            else -> return super.lookupValues(lookupId, query)
        }
        return runCatching {
            values.map { (label, value) ->
                FilterOption(
                    id = value,
                    label = label,
                    value = QueryValue.of(value),
                )
            }
        }
    }

    override suspend fun fetch(
        pushdownExpression: QueryExpression?,
        sort: CollectionSortSelection,
        offset: Int,
        limit: Int,
    ): Result<CatalogPage> {
        val compiled = MangaUpdatesCollectionCompiler.compile(pushdownExpression, sort)

        return PageOffsetNormalizer.load(
            rawOffset = offset,
            limit = limit,
            upstreamPageSize = capabilities.preferredPageSize ?: 50,
            pageOrigin = PageIndexOrigin.ONE,
            fetcher = PageCatalogFetcher { page, pageSize ->
                api.collectionSearch(
                    compiled.copy(
                        page = page,
                        perPage = pageSize,
                    ),
                ).map { response ->
                    CatalogPage(
                        items = response.items.map { it.toIntegrationCatalogItem(providerId) },
                        hasNextPage = response.page * response.perPage < response.totalHits,
                        totalCount = response.totalHits,
                    )
                }
            },
        )
    }

    companion object {
        internal fun forTest(
            api: MangaUpdatesIntegrationApi,
        ): MangaUpdatesCollectionQueryProvider = MangaUpdatesCollectionQueryProvider(api)
    }
}
