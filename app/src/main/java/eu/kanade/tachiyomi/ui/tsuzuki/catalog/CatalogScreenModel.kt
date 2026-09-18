package eu.kanade.tachiyomi.ui.tsuzuki.catalog

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tachiyomi.domain.tsuzuki.catalog.interactor.GetDiscoverFeed
import tachiyomi.domain.tsuzuki.catalog.interactor.SearchCatalog
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.DiscoverFeed

@Immutable
sealed interface DiscoverState {
    data object Loading : DiscoverState
    data class Success(val feed: DiscoverFeed) : DiscoverState
    data class Degraded(val feed: DiscoverFeed) : DiscoverState
    data class Error(val error: Throwable) : DiscoverState
}

@Immutable
sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Success(val items: List<CatalogItem>) : SearchState
    data object Empty : SearchState
    data class Error(val error: Throwable) : SearchState
}

@Immutable
sealed interface CatalogScreenState {
    val searchQuery: String
    val selectedItem: CatalogItem?
    val discoverState: DiscoverState
    val searchState: SearchState

    val isSearching: Boolean get() = searchQuery.isNotBlank()

    @Immutable
    data class Loading(
        override val searchQuery: String = "",
        override val selectedItem: CatalogItem? = null,
        override val discoverState: DiscoverState = DiscoverState.Loading,
        override val searchState: SearchState = SearchState.Idle,
    ) : CatalogScreenState

    @Immutable
    data class Success(
        override val searchQuery: String = "",
        override val selectedItem: CatalogItem? = null,
        override val discoverState: DiscoverState = DiscoverState.Loading,
        override val searchState: SearchState = SearchState.Idle,
        val discoverFeed: DiscoverFeed? = null,
        val searchResults: List<CatalogItem> = emptyList(),
    ) : CatalogScreenState

    @Immutable
    data class Empty(
        override val searchQuery: String = "",
        override val selectedItem: CatalogItem? = null,
        override val discoverState: DiscoverState = DiscoverState.Loading,
        override val searchState: SearchState = SearchState.Idle,
        val message: String? = null,
    ) : CatalogScreenState

    @Immutable
    data class Degraded(
        override val searchQuery: String = "",
        override val selectedItem: CatalogItem? = null,
        override val discoverState: DiscoverState = DiscoverState.Loading,
        override val searchState: SearchState = SearchState.Idle,
        val discoverFeed: DiscoverFeed,
        val reason: String? = null,
    ) : CatalogScreenState

    @Immutable
    data class Error(
        override val searchQuery: String = "",
        override val selectedItem: CatalogItem? = null,
        override val discoverState: DiscoverState = DiscoverState.Loading,
        override val searchState: SearchState = SearchState.Idle,
        val error: Throwable? = null,
        val message: String? = null,
    ) : CatalogScreenState
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class CatalogScreenModel(
    private val searchCatalog: SearchCatalog,
    private val getDiscoverFeed: GetDiscoverFeed,
) : ViewModel() {

    private val searchQueryFlow = MutableStateFlow("")
    private val selectedItemFlow = MutableStateFlow<CatalogItem?>(null)
    private val discoverStateFlow = MutableStateFlow<DiscoverState>(DiscoverState.Loading)
    private val searchStateFlow = MutableStateFlow<SearchState>(SearchState.Idle)

    private var discoverJob: Job? = null
    private var searchJob: Job? = null

    val state: StateFlow<CatalogScreenState> = combine(
        searchQueryFlow,
        selectedItemFlow,
        discoverStateFlow,
        searchStateFlow,
    ) { query, selected, discover, search ->
        computeState(query, selected, discover, search)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = CatalogScreenState.Loading(),
    )

    init {
        loadDiscover()
    }

    fun loadDiscover(): Job {
        discoverJob?.cancel()
        return viewModelScope.launch {
            refreshDiscover()
        }
    }

    suspend fun refreshDiscover() {
        discoverStateFlow.value = DiscoverState.Loading
        try {
            val feed = getDiscoverFeed()
            if (feed.isCompleteFailure) {
                val error = feed.trending.exceptionOrNull()
                    ?: feed.popular.exceptionOrNull()
                    ?: IllegalStateException("Complete feed failure")
                discoverStateFlow.value = DiscoverState.Error(error)
            } else if (feed.isDegraded) {
                discoverStateFlow.value = DiscoverState.Degraded(feed)
            } else {
                discoverStateFlow.value = DiscoverState.Success(feed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            discoverStateFlow.value = DiscoverState.Error(e)
        }
    }

    fun updateSearchQuery(query: String) {
        searchQueryFlow.value = query
        searchJob?.cancel()

        if (query.isBlank()) {
            searchStateFlow.value = SearchState.Idle
            return
        }

        searchStateFlow.value = SearchState.Loading
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            executeSearch(query)
        }
    }

    fun search(query: String = searchQueryFlow.value): Job {
        searchJob?.cancel()
        return viewModelScope.launch {
            executeSearch(query)
        }
    }

    suspend fun executeSearch(query: String) {
        searchQueryFlow.value = query
        if (query.isBlank()) {
            searchStateFlow.value = SearchState.Idle
            return
        }
        searchStateFlow.value = SearchState.Loading
        try {
            val result = searchCatalog(query = query)
            result.fold(
                onSuccess = { page ->
                    if (page.items.isEmpty()) {
                        searchStateFlow.value = SearchState.Empty
                    } else {
                        searchStateFlow.value = SearchState.Success(page.items)
                    }
                },
                onFailure = { error ->
                    searchStateFlow.value = SearchState.Error(error)
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            searchStateFlow.value = SearchState.Error(e)
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchQueryFlow.value = ""
        searchStateFlow.value = SearchState.Idle
    }

    fun openPreview(item: CatalogItem) {
        selectedItemFlow.value = item
    }

    fun dismissPreview() {
        selectedItemFlow.value = null
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 400L
    }

    private fun computeState(
        query: String,
        selected: CatalogItem?,
        discover: DiscoverState,
        search: SearchState,
    ): CatalogScreenState {
        return if (query.isNotBlank()) {
            when (search) {
                is SearchState.Idle, is SearchState.Loading -> CatalogScreenState.Loading(
                    searchQuery = query,
                    selectedItem = selected,
                    discoverState = discover,
                    searchState = search,
                )
                is SearchState.Success -> CatalogScreenState.Success(
                    searchQuery = query,
                    selectedItem = selected,
                    discoverState = discover,
                    searchState = search,
                    searchResults = search.items,
                )
                is SearchState.Empty -> CatalogScreenState.Empty(
                    searchQuery = query,
                    selectedItem = selected,
                    discoverState = discover,
                    searchState = search,
                    message = "No results found for \"$query\"",
                )
                is SearchState.Error -> CatalogScreenState.Error(
                    searchQuery = query,
                    selectedItem = selected,
                    discoverState = discover,
                    searchState = search,
                    error = search.error,
                    message = search.error.message ?: "Failed to search catalog",
                )
            }
        } else {
            when (discover) {
                is DiscoverState.Loading -> CatalogScreenState.Loading(
                    searchQuery = query,
                    selectedItem = selected,
                    discoverState = discover,
                    searchState = search,
                )
                is DiscoverState.Success -> CatalogScreenState.Success(
                    searchQuery = query,
                    selectedItem = selected,
                    discoverState = discover,
                    searchState = search,
                    discoverFeed = discover.feed,
                )
                is DiscoverState.Degraded -> CatalogScreenState.Degraded(
                    searchQuery = query,
                    selectedItem = selected,
                    discoverState = discover,
                    searchState = search,
                    discoverFeed = discover.feed,
                    reason = "Some catalog sections could not be loaded",
                )
                is DiscoverState.Error -> CatalogScreenState.Error(
                    searchQuery = query,
                    selectedItem = selected,
                    discoverState = discover,
                    searchState = search,
                    error = discover.error,
                    message = discover.error.message ?: "Failed to load discover feed",
                )
            }
        }
    }
}
