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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.tsuzuki.catalog.interactor.SearchIntegrations
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.model.mergeCatalogItemsByVerifiedIdentity
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
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
    private var operationGeneration = 0L

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
        val generation = ++operationGeneration
        val normalized = query.trim()
        operation = viewModelScope.launch {
            registry.awaitReady()
            if (generation != operationGeneration) return@launch
            if (normalized.isEmpty()) {
                loadDiscoverNow(generation)
                return@launch
            }

            searchPreferences.recordSearch(normalized)
            if (registry.searchProviders().isEmpty()) {
                if (generation == operationGeneration) {
                    _state.value = SearchState.NeedsIntegration(
                        recentSearches = searchPreferences.getRecentSearches(),
                    )
                }
                return@launch
            }

            _state.value = SearchState.Loading
            try {
                val baseItems = searchIntegrations.executeBaseProgressively(
                    query = CatalogQuery(query = normalized),
                ) { partialItems ->
                    if (generation == operationGeneration && partialItems.isNotEmpty()) {
                        _state.value = SearchState.Results(normalized, partialItems)
                    }
                }
                if (generation != operationGeneration) return@launch
                if (baseItems.isEmpty()) {
                    _state.value = SearchState.Empty(normalized)
                    return@launch
                }

                _state.value = SearchState.Results(normalized, baseItems)
                val enriched = searchIntegrations.enrichRatingsProgressively(baseItems) { index, item ->
                    if (generation != operationGeneration) return@enrichRatingsProgressively
                    val current = _state.value as? SearchState.Results ?: return@enrichRatingsProgressively
                    if (current.query != normalized || index !in current.items.indices) {
                        return@enrichRatingsProgressively
                    }
                    _state.value = current.copy(
                        items = current.items.toMutableList().apply { this[index] = item },
                    )
                }
                if (generation == operationGeneration) {
                    _state.value = SearchState.Results(normalized, enriched)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == operationGeneration) {
                    _state.value = SearchState.Error(normalized, error)
                }
            }
        }
        return operation!!
    }

    fun loadDiscover(): Job {
        operation?.cancel()
        val generation = ++operationGeneration
        operation = viewModelScope.launch {
            loadDiscoverNow(generation)
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

    private suspend fun loadDiscoverNow(generation: Long) {
        registry.awaitReady()
        if (generation != operationGeneration) return
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

        if (generation != operationGeneration) return
        _state.value = SearchState.Discover(
            recentSearches = recentSearches,
            blocks = emptyList(),
        )
        try {
            coroutineScope {
                val completedBlocks = Channel<DiscoverBlock>(DISCOVER_REQUEST_COUNT)
                val baseJobs = listOf(
                    launch {
                        completedBlocks.send(
                            discoverBlock(
                                kind = DiscoverKind.TRENDING,
                                providers = providers,
                            ) { provider ->
                                provider.trending(offset = 0, limit = DISCOVER_LIMIT)
                            },
                        )
                    },
                    launch {
                        completedBlocks.send(
                            discoverBlock(
                                kind = DiscoverKind.POPULAR,
                                providers = providers,
                            ) { provider ->
                                provider.popular(offset = 0, limit = DISCOVER_LIMIT)
                            },
                        )
                    },
                    launch {
                        completedBlocks.send(
                            discoverBlock(
                                kind = DiscoverKind.TOP_RATED,
                                providers = providers,
                            ) { provider ->
                                provider.topRated(offset = 0, limit = DISCOVER_LIMIT)
                            },
                        )
                    },
                    launch {
                        completedBlocks.send(
                            discoverBlock(
                                kind = DiscoverKind.FAVORITES,
                                providers = providers,
                            ) { provider ->
                                provider.favorites(offset = 0, limit = DISCOVER_LIMIT)
                            },
                        )
                    },
                    launch {
                        completedBlocks.send(
                            discoverBlock(
                                kind = DiscoverKind.RECENTLY_UPDATED,
                                providers = providers,
                            ) { provider ->
                                provider.recentlyUpdated(offset = 0, limit = DISCOVER_LIMIT)
                            },
                        )
                    },
                )
                val enrichmentJobs = mutableListOf<Job>()
                repeat(baseJobs.size) {
                    val block = completedBlocks.receive()
                    if (block.items.isNotEmpty()) {
                        publishDiscoverBlock(generation, block)
                        enrichmentJobs += launch {
                            val enriched = searchIntegrations.enrichRatingsProgressively(block.items) { index, item ->
                                publishDiscoverItem(
                                    generation = generation,
                                    kind = block.kind,
                                    index = index,
                                    item = item,
                                )
                            }
                            publishDiscoverBlock(
                                generation = generation,
                                block = block.copy(items = enriched),
                            )
                        }
                    }
                }
                baseJobs.forEach { it.join() }
                enrichmentJobs.forEach { it.join() }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (generation == operationGeneration) {
                _state.value = SearchState.Error(
                    query = null,
                    error = error,
                )
            }
        }
    }

    private fun publishDiscoverBlock(
        generation: Long,
        block: DiscoverBlock,
    ) {
        if (generation != operationGeneration) return
        _state.update { current ->
            val discover = current as? SearchState.Discover ?: return@update current
            val blocks = discover.blocks.associateBy(DiscoverBlock::kind).toMutableMap()
            if (block.items.isEmpty()) {
                blocks.remove(block.kind)
            } else {
                blocks[block.kind] = block
            }
            discover.copy(
                blocks = blocks.values.sortedBy { it.kind.ordinal },
            )
        }
    }

    private fun publishDiscoverItem(
        generation: Long,
        kind: DiscoverKind,
        index: Int,
        item: CatalogItem,
    ) {
        if (generation != operationGeneration) return
        _state.update { current ->
            val discover = current as? SearchState.Discover ?: return@update current
            val blockIndex = discover.blocks.indexOfFirst { it.kind == kind }
            if (blockIndex < 0) return@update current
            val block = discover.blocks[blockIndex]
            if (index !in block.items.indices) return@update current
            discover.copy(
                blocks = discover.blocks.toMutableList().apply {
                    this[blockIndex] = block.copy(
                        items = block.items.toMutableList().apply {
                            this[index] = item
                        },
                    )
                },
            )
        }
    }

    private suspend fun discoverBlock(
        kind: DiscoverKind,
        providers: List<DiscoveryProvider>,
        request: suspend (DiscoveryProvider) -> Result<CatalogPage>,
    ): DiscoverBlock {
        val items = coroutineScope {
            providers.map { provider ->
                async {
                    try {
                        val result = request(provider)
                        val error = result.exceptionOrNull()
                        if (error is CancellationException) throw error
                        result.getOrElse { emptyPage() }.items
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }.let(::mergeCatalogItemsByVerifiedIdentity)

        return DiscoverBlock(
            kind = kind,
            items = items,
        )
    }

    private fun emptyPage() = CatalogPage(
        items = emptyList(),
        hasNextPage = false,
    )

    private companion object {
        const val DISCOVER_LIMIT = 20
        const val DISCOVER_REQUEST_COUNT = 5
    }
}
