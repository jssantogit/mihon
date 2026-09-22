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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import tachiyomi.domain.tsuzuki.catalog.interactor.SearchIntegrations
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
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
                _state.value = if (items.isEmpty()) {
                    SearchState.Empty(normalized)
                } else {
                    SearchState.Results(normalized, items)
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
            }
            _state.value = SearchState.Discover(
                recentSearches = recentSearches,
                blocks = blocks,
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
        }.distinctBy { item ->
            item.provider to item.providerId
        }

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
    }
}
