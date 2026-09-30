package eu.kanade.tachiyomi.ui.tsuzuki.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tachiyomi.domain.tsuzuki.catalog.interactor.SearchIntegrations
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.model.CatalogScore
import tachiyomi.domain.tsuzuki.catalog.model.mergeCatalogItemsByVerifiedIdentity
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitleFromCatalog

@Immutable
sealed interface SearchState {
    data object Loading : SearchState

    data class NeedsIntegration(
        val recentSearches: List<String> = emptyList(),
    ) : SearchState

    data class Discover(
        val recentSearches: List<String>,
        val blocks: List<DiscoverBlock>,
    ) : SearchState

    data class Results(
        val query: String,
        val items: List<CatalogItem>,
    ) : SearchState

    data class Empty(
        val query: String,
    ) : SearchState

    data class Error(
        val query: String?,
        val error: Throwable,
    ) : SearchState
}

@Immutable
data class DiscoverBlock(
    val kind: DiscoverKind,
    val items: List<CatalogItem>,
)

enum class DiscoverKind {
    TRENDING,
    POPULAR,
    TOP_RATED,
    FAVORITES,
    RECENTLY_UPDATED,
}

sealed interface TsuzukiSearchEvent {
    data class OpenCanonicalTitle(
        val canonicalTitleId: String,
    ) : TsuzukiSearchEvent
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class TsuzukiSearchScreenModel(
    private val searchIntegrations: SearchIntegrations,
    private val registry: IntegrationRegistry,
    private val searchPreferences: TsuzukiSearchPreferences,
    private val materializeCanonicalTitleFromCatalog: MaterializeCanonicalTitleFromCatalog,
) : ViewModel() {

    private val _state = MutableStateFlow<SearchState>(SearchState.Loading)
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private val eventChannel = Channel<TsuzukiSearchEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private var operation: Job? = null

    init {
        loadDiscover()
        viewModelScope.launch {
            registry.observeChanges().collectLatest {
                loadDiscover()
            }
        }
    }

    fun search(query: String): Job {
        operation?.cancel()
        val normalized = query.trim()
        operation = viewModelScope.launch {
            registry.awaitReady()
            if (normalized.isEmpty()) {
                loadDiscoverNow()
                return@launch
            }

            searchPreferences.recordSearch(normalized)
            if (registry.searchProviders().isEmpty()) {
                _state.value = SearchState.NeedsIntegration(
                    recentSearches = searchPreferences.getRecentSearches(),
                )
                return@launch
            }

            _state.value = SearchState.Loading
            try {
                val items = searchIntegrations.execute(
                    CatalogQuery(query = normalized),
                )
                if (items.isEmpty()) {
                    _state.value = SearchState.Empty(normalized)
                } else {
                    val baseItems = items.map(::normalizeScores)
                    _state.value = SearchState.Results(normalized, baseItems)

                    val enrichedItems = enrichRatings(baseItems)
                    if (enrichedItems != baseItems) {
                        _state.value = SearchState.Results(normalized, enrichedItems)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.value = SearchState.Error(normalized, error)
            }
        }
        return operation!!
    }

    fun loadDiscover(): Job {
        operation?.cancel()
        operation = viewModelScope.launch {
            loadDiscoverNow()
        }
        return operation!!
    }

    fun openResult(item: CatalogItem): Job? {
        val materializer = materializeCanonicalTitleFromCatalog ?: return null
        return viewModelScope.launch {
            try {
                val title = materializer.execute(item)
                eventChannel.send(
                    TsuzukiSearchEvent.OpenCanonicalTitle(title.id),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.value = SearchState.Error(
                    query = null,
                    error = error,
                )
            }
        }
    }

    fun clearRecentSearches() {
        searchPreferences.clear()
        loadDiscover()
    }

    private suspend fun loadDiscoverNow() {
        registry.awaitReady()
        val recentSearches = searchPreferences.getRecentSearches()
        val providers = registry.discoveryProviders()
        if (providers.isEmpty()) {
            _state.value = if (
                recentSearches.isEmpty() &&
                registry.searchProviders().isEmpty()
            ) {
                SearchState.NeedsIntegration()
            } else {
                SearchState.Discover(
                    recentSearches = recentSearches,
                    blocks = emptyList(),
                )
            }
            return
        }

        _state.value = SearchState.Loading
        try {
            val blocks = coroutineScope {
                listOf(
                    async {
                        discoverBlock(
                            kind = DiscoverKind.TRENDING,
                            providers = providers,
                        ) { provider ->
                            provider.trending(
                                offset = 0,
                                limit = DISCOVER_LIMIT,
                            )
                        }
                    },
                    async {
                        discoverBlock(
                            kind = DiscoverKind.POPULAR,
                            providers = providers,
                        ) { provider ->
                            provider.popular(
                                offset = 0,
                                limit = DISCOVER_LIMIT,
                            )
                        }
                    },
                    async {
                        discoverBlock(
                            kind = DiscoverKind.TOP_RATED,
                            providers = providers,
                        ) { provider ->
                            provider.topRated(
                                offset = 0,
                                limit = DISCOVER_LIMIT,
                            )
                        }
                    },
                    async {
                        discoverBlock(
                            kind = DiscoverKind.FAVORITES,
                            providers = providers,
                        ) { provider ->
                            provider.favorites(
                                offset = 0,
                                limit = DISCOVER_LIMIT,
                            )
                        }
                    },
                    async {
                        discoverBlock(
                            kind = DiscoverKind.RECENTLY_UPDATED,
                            providers = providers,
                        ) { provider ->
                            provider.recentlyUpdated(
                                offset = 0,
                                limit = DISCOVER_LIMIT,
                            )
                        }
                    },
                ).awaitAll()
                    .filter { block -> block.items.isNotEmpty() }
                    .let(::shareRatingsAcrossBlocks)
            }
            _state.value = SearchState.Discover(
                recentSearches = recentSearches,
                blocks = blocks,
            )

            val enrichedBlocks = enrichRatingsAcrossBlocks(blocks)
            if (enrichedBlocks != blocks) {
                _state.value = SearchState.Discover(
                    recentSearches = recentSearches,
                    blocks = enrichedBlocks,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            _state.value = SearchState.Error(
                query = null,
                error = error,
            )
        }
    }

    private suspend fun discoverBlock(
        kind: DiscoverKind,
        providers: List<DiscoveryProvider>,
        request: suspend (DiscoveryProvider) -> Result<CatalogPage>,
    ): DiscoverBlock {
        val orderedProviders = providers.sortedWith(
            compareBy<DiscoveryProvider>(
                { provider ->
                    DISCOVERY_PROVIDER_PRECEDENCE.indexOf(provider.integrationId.value)
                        .takeIf { index -> index >= 0 }
                        ?: Int.MAX_VALUE
                },
                { provider -> provider.integrationId.value },
            ),
        )

        for (provider in orderedProviders) {
            val items = try {
                val result = request(provider)
                val error = result.exceptionOrNull()
                if (error is CancellationException) throw error
                result.getOrNull()?.items.orEmpty()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                emptyList()
            }

            if (items.isNotEmpty()) {
                return DiscoverBlock(
                    kind = kind,
                    items = items
                        .distinctBy { item -> item.provider to item.providerId }
                        .map(::normalizeScores),
                )
            }
        }

        return DiscoverBlock(
            kind = kind,
            items = emptyList(),
        )
    }

    private suspend fun enrichRatingsAcrossBlocks(
        blocks: List<DiscoverBlock>,
    ): List<DiscoverBlock> {
        if (blocks.isEmpty()) return blocks

        val uniqueItems = mergeCatalogItemsByVerifiedIdentity(
            blocks.flatMap(DiscoverBlock::items),
        )
        val enrichedItems = enrichRatings(uniqueItems)

        return blocks.map { block ->
            block.copy(
                items = block.items.map { item ->
                    val itemKeys = item.identityKeysForDiscovery()
                    val enriched = enrichedItems.firstOrNull { candidate ->
                        candidate.identityKeysForDiscovery().any(itemKeys::contains)
                    } ?: item
                    item.mergeEnrichment(enriched)
                },
            )
        }
    }

    private suspend fun enrichRatings(items: List<CatalogItem>): List<CatalogItem> {
        val providers = registry.ratingsProviders()
            .sortedWith(
                compareBy<RatingsProvider>(
                    { provider ->
                        RATING_PROVIDER_PRECEDENCE.indexOf(provider.integrationId.value)
                            .takeIf { index -> index >= 0 }
                            ?: Int.MAX_VALUE
                    },
                    { provider -> provider.integrationId.value },
                ),
            )
        if (providers.isEmpty()) return items

        val semaphore = Semaphore(RATING_ENRICHMENT_CONCURRENCY)
        return coroutineScope {
            items.map { item ->
                async {
                    semaphore.withPermit {
                        enrichRatings(item, providers)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun enrichRatings(
        item: CatalogItem,
        providers: List<RatingsProvider>,
    ): CatalogItem {
        var enriched = normalizeScores(item)

        for (provider in providers) {
            if (enriched.scores.any { score -> score.provider == provider.integrationId.value }) {
                continue
            }

            val resolution = try {
                val result = provider.resolveRatings(enriched)
                val error = result.exceptionOrNull()
                if (error is CancellationException) throw error
                result.getOrNull()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            } ?: continue

            val resolvedScores = resolution.ratings.map { rating ->
                CatalogScore(
                    provider = rating.providerId,
                    value = rating.value,
                    maxValue = rating.scaleMax,
                )
            }
            val scores = normalizeScores(enriched.scores + resolvedScores)
            enriched = enriched.copy(
                externalIds = enriched.externalIds +
                    (provider.integrationId.value to resolution.externalId),
                score = scores.firstOrNull(),
                scores = scores,
            )
        }

        return enriched
    }

    private fun CatalogItem.mergeEnrichment(enriched: CatalogItem): CatalogItem {
        val scores = normalizeScores(
            scores.ifEmpty { listOfNotNull(score) } +
                enriched.scores.ifEmpty { listOfNotNull(enriched.score) },
        )
        return copy(
            externalIds = externalIds + enriched.externalIds,
            score = scores.firstOrNull(),
            scores = scores,
        )
    }

    private fun normalizeScores(item: CatalogItem): CatalogItem {
        val scores = normalizeScores(item.scores.ifEmpty { listOfNotNull(item.score) })
        return item.copy(
            score = scores.firstOrNull(),
            scores = scores,
        )
    }

    private fun normalizeScores(scores: List<CatalogScore>): List<CatalogScore> =
        scores
            .distinctBy(CatalogScore::provider)
            .sortedWith(
                compareBy<CatalogScore>(
                    { score ->
                        RATING_PROVIDER_PRECEDENCE.indexOf(score.provider)
                            .takeIf { index -> index >= 0 }
                            ?: Int.MAX_VALUE
                    },
                    CatalogScore::provider,
                ),
            )

    private fun shareRatingsAcrossBlocks(blocks: List<DiscoverBlock>): List<DiscoverBlock> {
        val scoresByIdentity = mutableMapOf<Pair<String, String>, MutableList<CatalogScore>>()

        blocks.asSequence()
            .flatMap { it.items.asSequence() }
            .forEach { item ->
                val scores = item.scores.ifEmpty { listOfNotNull(item.score) }
                item.identityKeysForDiscovery().forEach { identity ->
                    scoresByIdentity.getOrPut(identity) { mutableListOf() } += scores
                }
            }

        return blocks.map { block ->
            block.copy(
                items = block.items.map { item ->
                    val ownScores = item.scores.ifEmpty { listOfNotNull(item.score) }
                    val sharedScores = item.identityKeysForDiscovery()
                        .flatMap { identity -> scoresByIdentity[identity].orEmpty() }
                    val scores = normalizeScores(ownScores + sharedScores)
                    item.copy(
                        score = scores.firstOrNull(),
                        scores = scores,
                    )
                },
            )
        }
    }

    private fun CatalogItem.identityKeysForDiscovery(): Set<Pair<String, String>> = buildSet {
        add(provider to providerId)
        externalIds.forEach { (providerId, externalId) ->
            if (providerId.isNotBlank() && externalId.isNotBlank()) {
                add(providerId to externalId)
            }
        }
    }

    private companion object {
        const val DISCOVER_LIMIT = 20
        const val RATING_ENRICHMENT_CONCURRENCY = 6

        // A semantic catalog is owned by one provider at a time. Other active providers fill
        // catalog kinds that the preferred provider does not expose; they never create a second
        // copy of the same semantic section.
        val DISCOVERY_PROVIDER_PRECEDENCE = listOf(
            "kitsu",
            "mal",
            "mangaupdates",
            "bangumi",
        )
        val RATING_PROVIDER_PRECEDENCE = listOf(
            "mal",
            "kitsu",
            "mangaupdates",
            "bangumi",
        )
    }
}
