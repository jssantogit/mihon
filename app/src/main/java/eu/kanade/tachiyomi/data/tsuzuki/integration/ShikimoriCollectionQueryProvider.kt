package eu.kanade.tachiyomi.data.tsuzuki.integration

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.shikimori.ShikimoriCollectionQuery
import eu.kanade.tachiyomi.data.track.shikimori.ShikimoriIntegrationApi
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.collections.capability.CollectionFilterCapability
import tachiyomi.domain.tsuzuki.collections.capability.CollectionPagingCapability
import tachiyomi.domain.tsuzuki.collections.capability.CollectionPagingMode
import tachiyomi.domain.tsuzuki.collections.capability.CollectionProviderDescriptor
import tachiyomi.domain.tsuzuki.collections.capability.CollectionProviderScope
import tachiyomi.domain.tsuzuki.collections.capability.CollectionRequestBudget
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

object ShikimoriCollectionCapabilities : ProviderQueryCapabilities {

    override val descriptor = CollectionProviderDescriptor(
        providerId = PROVIDER_ID,
        displayName = "Shikimori",
        scope = CollectionProviderScope.GLOBAL,
        filters = listOf(
            CollectionFilterCapability(
                id = "kind",
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
                        option("released", "Released", CatalogItemStatus.COMPLETED.name),
                        option("paused", "Paused", CatalogItemStatus.ON_HIATUS.name),
                        option("discontinued", "Discontinued", CatalogItemStatus.CANCELLED.name),
                    ),
                ),
                multiValueMode = MultiValueMode.INCLUDE_EXCLUDE,
            ),
            CollectionFilterCapability(
                id = "season",
                field = QueryField.START_YEAR,
                placement = FilterPlacement.ADVANCED,
                operators = setOf(QueryOperator.EQUALS, QueryOperator.BETWEEN),
                execution = setOf(FilterExecutionMode.REMOTE_EXACT),
                valueSource = FilterValueSource.IntegerRange,
                multiValueMode = MultiValueMode.SINGLE,
            ),
            CollectionFilterCapability(
                id = "minimum_score",
                field = QueryField.SCORE,
                placement = FilterPlacement.QUICK,
                operators = setOf(QueryOperator.GREATER_OR_EQUAL),
                execution = setOf(FilterExecutionMode.REMOTE_EXACT),
                valueSource = FilterValueSource.IntegerRange,
                multiValueMode = MultiValueMode.SINGLE,
            ),
            CollectionFilterCapability(
                id = "genre",
                field = QueryField.GENRE,
                placement = FilterPlacement.ADVANCED,
                operators = setOf(QueryOperator.EQUALS),
                execution = setOf(FilterExecutionMode.REMOTE_EXACT),
                valueSource = FilterValueSource.RemoteLookup("shikimori.genres"),
                multiValueMode = MultiValueMode.INCLUDE_EXCLUDE,
            ),
            CollectionFilterCapability(
                id = "publisher",
                field = QueryField.PUBLISHER,
                placement = FilterPlacement.ADVANCED,
                operators = setOf(QueryOperator.EQUALS),
                execution = setOf(FilterExecutionMode.REMOTE_EXACT),
                valueSource = FilterValueSource.RemoteLookup("shikimori.publishers"),
                multiValueMode = MultiValueMode.INCLUDE_EXCLUDE,
            ),
            CollectionFilterCapability(
                id = "franchise",
                field = QueryField.Custom("shikimori.franchise"),
                placement = FilterPlacement.ADVANCED,
                operators = setOf(QueryOperator.EQUALS),
                execution = setOf(FilterExecutionMode.REMOTE_EXACT),
                valueSource = FilterValueSource.FreeText,
                multiValueMode = MultiValueMode.INCLUDE_EXCLUDE,
            ),
            CollectionFilterCapability(
                id = "censored",
                field = QueryField.Custom("shikimori.censored"),
                placement = FilterPlacement.ADVANCED,
                operators = setOf(QueryOperator.EQUALS),
                execution = setOf(FilterExecutionMode.REMOTE_EXACT),
                valueSource = FilterValueSource.BooleanToggle,
                multiValueMode = MultiValueMode.SINGLE,
            ),
            CollectionFilterCapability(
                id = "query",
                field = QueryField.Custom("shikimori.query"),
                placement = FilterPlacement.ADVANCED,
                operators = setOf(QueryOperator.EQUALS),
                execution = setOf(FilterExecutionMode.REMOTE_EXACT),
                valueSource = FilterValueSource.FreeText,
                multiValueMode = MultiValueMode.SINGLE,
            ),
        ),
        sorts = listOf(
            nativeSort("popularity", "Popularity"),
            nativeSort("ranked", "Ranked"),
            nativeSort("name", "Name"),
            nativeSort("aired_on", "Start date"),
        ),
        paging = CollectionPagingCapability(
            mode = CollectionPagingMode.PAGE,
            maxPageSize = PAGE_SIZE,
            preferredPageSize = PAGE_SIZE,
        ),
        requestBudget = CollectionRequestBudget(
            maxRequestsPerSecond = 5,
            maxRequestsPerMinute = 90,
        ),
    )

    override fun canPushExpression(expression: QueryExpression): Boolean = when (expression) {
        is QueryExpression.Predicate -> canPushPredicate(
            expression.field,
            expression.operator,
            expression.value,
        )
        is QueryExpression.Not -> {
            val predicate = expression.expression as? QueryExpression.Predicate ?: return false
            predicate.field in NEGATABLE_FIELDS && canPushPredicate(
                predicate.field,
                predicate.operator,
                predicate.value,
            )
        }
        is QueryExpression.All -> {
            expression.expressions.all(::canPushExpression) &&
                expression.expressions
                    .mapNotNull { term ->
                        when (term) {
                            is QueryExpression.Predicate -> term.field
                            is QueryExpression.Not -> (term.expression as? QueryExpression.Predicate)?.field
                            else -> null
                        }
                    }
                    .groupingBy { it }
                    .eachCount()
                    .values
                    .none { it > 1 }
        }
        is QueryExpression.Any -> false
    }

    private fun option(id: String, label: String, value: String) =
        FilterOption(id, label, QueryValue.of(value))

    private fun nativeSort(id: String, label: String) = CollectionSortCapability(
        key = CollectionSortKey.Provider(PROVIDER_ID, id),
        label = label,
        directionMode = SortDirectionMode.FIXED_NATIVE,
    )

    private const val PROVIDER_ID = "shikimori"
    private const val PAGE_SIZE = 50
    private val NEGATABLE_FIELDS = setOf(
        QueryField.WORK_TYPE,
        QueryField.STATUS,
        QueryField.GENRE,
        QueryField.PUBLISHER,
        QueryField.Custom("shikimori.franchise"),
    )
}

object ShikimoriCollectionCompiler {

    fun compile(
        expression: QueryExpression?,
        sort: CollectionSortSelection,
    ): ShikimoriCollectionQuery {
        require(ShikimoriCollectionCapabilities.canPushSort(sort)) {
            "Unsupported Shikimori Collection sort: $sort"
        }
        if (expression != null) {
            require(ShikimoriCollectionCapabilities.canPushExpression(expression)) {
                "Unsupported Shikimori Collection pushdown: ${expression.toCanonicalString()}"
            }
        }

        var kind: String? = null
        var status: String? = null
        var season: String? = null
        var score: Int? = null
        var genre: String? = null
        var publisher: String? = null
        var franchise: String? = null
        var censored: Boolean? = null
        var search: String? = null

        fun collect(term: QueryExpression, negative: Boolean = false) {
            when (term) {
                is QueryExpression.Predicate -> {
                    val prefix = if (negative) "!" else ""
                    when (term.field) {
                        QueryField.WORK_TYPE ->
                            kind = prefix + mapKind((term.value as QueryValue.StringValue).value)
                        QueryField.STATUS ->
                            status = prefix + mapStatus((term.value as QueryValue.StringValue).value)
                        QueryField.START_YEAR -> season = seasonValue(term)
                        QueryField.SCORE -> score = term.value.integerValue()
                        QueryField.GENRE ->
                            genre = prefix + (term.value as QueryValue.StringValue).value
                        QueryField.PUBLISHER ->
                            publisher = prefix + (term.value as QueryValue.StringValue).value
                        QueryField.Custom("shikimori.franchise") ->
                            franchise = prefix + (term.value as QueryValue.StringValue).value
                        QueryField.Custom("shikimori.censored") ->
                            censored = (term.value as QueryValue.BooleanValue).value
                        QueryField.Custom("shikimori.query") ->
                            search = (term.value as QueryValue.StringValue).value
                        else -> error("Capability/compiler disagreement for ${term.field.identifier}")
                    }
                }
                is QueryExpression.Not -> collect(term.expression, negative = true)
                is QueryExpression.All -> term.expressions.forEach(::collect)
                is QueryExpression.Any -> error("ANY cannot reach Shikimori compiler")
            }
        }

        expression?.let(::collect)
        val key = sort.key as CollectionSortKey.Provider
        return ShikimoriCollectionQuery(
            page = 1,
            limit = 50,
            order = key.nativeId,
            kind = kind,
            status = status,
            season = season,
            score = score,
            genre = genre,
            publisher = publisher,
            franchise = franchise,
            censored = censored,
            search = search,
        )
    }

    private fun mapKind(value: String): String = when (value.uppercase()) {
        CatalogItemFormat.MANGA.name -> "manga"
        CatalogItemFormat.MANHWA.name -> "manhwa"
        CatalogItemFormat.MANHUA.name -> "manhua"
        CatalogItemFormat.ONE_SHOT.name -> "one_shot"
        CatalogItemFormat.DOUJIN.name -> "doujin"
        else -> error("Unsupported Shikimori kind '$value'")
    }

    private fun mapStatus(value: String): String = when (value.uppercase()) {
        CatalogItemStatus.ONGOING.name -> "ongoing"
        CatalogItemStatus.COMPLETED.name -> "released"
        CatalogItemStatus.ON_HIATUS.name -> "paused"
        CatalogItemStatus.CANCELLED.name -> "discontinued"
        else -> error("Unsupported Shikimori status '$value'")
    }

    private fun seasonValue(predicate: QueryExpression.Predicate): String = when (predicate.operator) {
        QueryOperator.EQUALS -> predicate.value.integerValue().toString()
        QueryOperator.BETWEEN -> {
            val range = predicate.value as QueryValue.RangeValue
            "${range.lower.integerValue()}_${range.upper.integerValue()}"
        }
        else -> error("Unsupported Shikimori season operator")
    }

    private fun QueryValue.integerValue(): Int = when (this) {
        is QueryValue.IntegerValue -> value.toInt()
        is QueryValue.DoubleValue -> {
            require(value % 1.0 == 0.0) { "Shikimori score/year must be integral" }
            value.toInt()
        }
        else -> error("Expected integer Shikimori value")
    }
}

@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<CollectionQueryProvider>())
class ShikimoriCollectionQueryProvider private constructor(
    private val api: ShikimoriIntegrationApi,
    private val requestGate: ShikimoriRequestGate,
) : CollectionQueryProvider {

    @Inject
    constructor(
        trackerManager: TrackerManager,
        requestGate: ShikimoriRequestGate,
    ) : this(
        api = trackerManager.shikimori.integrationApi,
        requestGate = requestGate,
    )

    override val providerId = "shikimori"
    override val capabilities: ProviderQueryCapabilities = ShikimoriCollectionCapabilities

    override suspend fun fetch(
        pushdownExpression: QueryExpression?,
        sort: CollectionSortSelection,
        offset: Int,
        limit: Int,
    ): Result<CatalogPage> {
        val compiled = ShikimoriCollectionCompiler.compile(pushdownExpression, sort)
        return PageOffsetNormalizer.load(
            rawOffset = offset,
            limit = limit,
            upstreamPageSize = capabilities.preferredPageSize ?: 50,
            pageOrigin = PageIndexOrigin.ONE,
            fetcher = PageCatalogFetcher { page, pageSize ->
                runCatching {
                    val response = requestGate.withPermit {
                        api.collectionSearch(
                            compiled.copy(page = page, limit = pageSize),
                        )
                    }
                    CatalogPage(
                        items = response.items.map { it.toIntegrationCatalogItem(providerId) },
                        hasNextPage = response.hasNextPage,
                    )
                }
            },
        )
    }

    companion object {
        internal fun forTest(
            api: ShikimoriIntegrationApi,
            requestGate: ShikimoriRequestGate = ShikimoriRequestGate(),
        ): ShikimoriCollectionQueryProvider =
            ShikimoriCollectionQueryProvider(api, requestGate)
    }
}
