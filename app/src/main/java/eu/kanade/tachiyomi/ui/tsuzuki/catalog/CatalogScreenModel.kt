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
import tachiyomi.domain.tsuzuki.library.interactor.AddCatalogItemToLibrary
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

@Immutable
sealed interface LibraryActionState {
    data object Idle : LibraryActionState
    data object Saving : LibraryActionState
    data class Saved(val canonicalTitleId: String) : LibraryActionState
    data class Error(val error: Throwable) : LibraryActionState
}

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
    val libraryActionState: LibraryActionState

    val isSearching: Boolean get() = searchQuery.isNotBlank()

    @Immutable
    data class Loading(
        override val searchQuery: String = "",
        override val selectedItem: CatalogItem? = null,
        override val discoverState: DiscoverState = DiscoverState.Loading,
        override val searchState: SearchState = SearchState.Idle,
        override val libraryActionState: LibraryActionState = LibraryActionState.Idle,
    ) : CatalogScreenState

    @Immutable
    data class Success(
        override val searchQuery: String = "",
        override val selectedItem: CatalogItem? = null,
        override val discoverState: DiscoverState = DiscoverState.Loading,
        override val searchState: SearchState = SearchState.Idle,
        override val libraryActionState: LibraryActionState = LibraryActionState.Idle,
        val discoverFeed: DiscoverFeed? = null,
        val searchResults: List<CatalogItem> = emptyList(),
    ) : CatalogScreenState

    @Immutable
    data class Empty(
        override val searchQuery: String = "",
        override val selectedItem: CatalogItem? = null,
        override val discoverState: DiscoverState = DiscoverState.Loading,
        override val searchState: SearchState = SearchState.Idle,
        override val libraryActionState: LibraryActionState = LibraryActionState.Idle,
        val message: String? = null,
    ) : CatalogScreenState

    @Immutable
    data class Degraded(
        override val searchQuery: String = "",
        override val selectedItem: CatalogItem? = null,
        override val discoverState: DiscoverState = DiscoverState.Loading,
        override val searchState: SearchState = SearchState.Idle,
        override val libraryActionState: LibraryActionState = LibraryActionState.Idle,
        val discoverFeed: DiscoverFeed,
        val reason: String? = null,
    ) : CatalogScreenState

    @Immutable
    data class Error(
        override val searchQuery: String = "",
        override val selectedItem: CatalogItem? = null,
        override val discoverState: DiscoverState = DiscoverState.Loading,
        override val searchState: SearchState = SearchState.Idle,
        override val libraryActionState: LibraryActionState = LibraryActionState.Idle,
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
    private val addCatalogItemToLibrary: AddCatalogItemToLibrary,
    private val canonicalTitleRepository: CanonicalTitleRepository,
    private val canonicalLibraryRepository: CanonicalLibraryRepository,
) : ViewModel() {

    private val searchQueryFlow = MutableStateFlow("")
    private val selectedItemFlow = MutableStateFlow<CatalogItem?>(null)
    private val discoverStateFlow = MutableStateFlow<DiscoverState>(DiscoverState.Loading)
    private val searchStateFlow = MutableStateFlow<SearchState>(SearchState.Idle)
    private val libraryActionStateFlow = MutableStateFlow<LibraryActionState>(LibraryActionState.Idle)

    private var discoverJob: Job? = null
    private var searchJob: Job? = null
    private var previewJob: Job? = null
    private var searchGeneration = 0L

    val state: StateFlow<CatalogScreenState> = combine(
        searchQueryFlow,
        selectedItemFlow,
        discoverStateFlow,
        searchStateFlow,
        libraryActionStateFlow,
    ) { query, selected, discover, search, libraryAction ->
        computeState(query, selected, discover, search, libraryAction)
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
            val baseFeed = getDiscoverFeed.awaitBase()
            publishDiscover(baseFeed)
            if (!baseFeed.isCompleteFailure) {
                try {
                    publishDiscover(getDiscoverFeed.enrich(baseFeed))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Catalog content is already visible; enrichment is best-effort.
                }
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
        val generation = ++searchGeneration

        if (query.isBlank()) {
            searchStateFlow.value = SearchState.Idle
            return
        }

        searchStateFlow.value = SearchState.Loading
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            executeSearch(query, generation)
        }
    }

    fun search(query: String = searchQueryFlow.value): Job {
        searchJob?.cancel()
        val generation = ++searchGeneration
        val job = viewModelScope.launch {
            executeSearch(query, generation)
        }
        searchJob = job
        return job
    }

    suspend fun executeSearch(query: String) {
        val generation = ++searchGeneration
        executeSearch(query, generation)
    }

    private suspend fun executeSearch(
        query: String,
        generation: Long,
    ) {
        if (!isCurrentSearch(query, generation, allowUnpublishedQuery = true)) return
        searchQueryFlow.value = query
        if (query.isBlank()) {
            if (generation == searchGeneration) searchStateFlow.value = SearchState.Idle
            return
        }
        searchStateFlow.value = SearchState.Loading
        try {
            val result = searchCatalog.awaitBase(query = query)
            result.fold(
                onSuccess = { page ->
                    if (!isCurrentSearch(query, generation)) return@fold
                    if (page.items.isEmpty()) {
                        searchStateFlow.value = SearchState.Empty
                    } else {
                        searchStateFlow.value = SearchState.Success(page.items)
                        try {
                            val enriched = searchCatalog.enrichProgressively(page) { index, item ->
                                if (!isCurrentSearch(query, generation)) return@enrichProgressively
                                val currentItems = (searchStateFlow.value as? SearchState.Success)?.items
                                    ?: page.items
                                if (index in currentItems.indices) {
                                    searchStateFlow.value = SearchState.Success(
                                        currentItems.toMutableList().apply { this[index] = item },
                                    )
                                }
                            }
                            if (isCurrentSearch(query, generation)) {
                                searchStateFlow.value = SearchState.Success(enriched.items)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            // Keep the fast catalog result if optional enrichment fails.
                        }
                    }
                },
                onFailure = { error ->
                    if (isCurrentSearch(query, generation)) {
                        searchStateFlow.value = SearchState.Error(error)
                    }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (isCurrentSearch(query, generation)) {
                searchStateFlow.value = SearchState.Error(e)
            }
        }
    }

    private fun isCurrentSearch(
        query: String,
        generation: Long,
        allowUnpublishedQuery: Boolean = false,
    ): Boolean = generation == searchGeneration &&
        (allowUnpublishedQuery || searchQueryFlow.value == query)

    fun clearSearch() {
        searchJob?.cancel()
        searchGeneration++
        searchQueryFlow.value = ""
        searchStateFlow.value = SearchState.Idle
    }

    fun openPreview(item: CatalogItem) {
        previewJob?.cancel()
        selectedItemFlow.value = item
        libraryActionStateFlow.value = LibraryActionState.Idle
        previewJob = viewModelScope.launch {
            try {
                val canonicalTitle = canonicalTitleRepository.getByExternalIdentity(
                    provider = item.provider,
                    externalId = item.providerId,
                )
                val libraryEntry = canonicalTitle?.let { canonicalLibraryRepository.get(it.id) }
                if (selectedItemFlow.value == item && canonicalTitle != null && libraryEntry != null) {
                    libraryActionStateFlow.value = LibraryActionState.Saved(canonicalTitle.id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Membership lookup is best-effort; explicit add remains idempotent.
            }
        }
    }

    fun dismissPreview() {
        previewJob?.cancel()
        selectedItemFlow.value = null
        libraryActionStateFlow.value = LibraryActionState.Idle
    }

    fun addToLibrary(
        item: CatalogItem,
        status: LibraryStatus = LibraryStatus.PLANNING,
    ): Job {
        libraryActionStateFlow.value = LibraryActionState.Saving
        return viewModelScope.launch {
            try {
                val result = addCatalogItemToLibrary.execute(item, status)
                libraryActionStateFlow.value = LibraryActionState.Saved(result.title.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                libraryActionStateFlow.value = LibraryActionState.Error(e)
            }
        }
    }

    private fun publishDiscover(feed: DiscoverFeed) {
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
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 400L
    }

    private fun computeState(
        query: String,
        selected: CatalogItem?,
        discover: DiscoverState,
        search: SearchState,
        libraryAction: LibraryActionState,
    ): CatalogScreenState {
        return if (query.isNotBlank()) {
            when (search) {
                is SearchState.Idle, is SearchState.Loading -> CatalogScreenState.Loading(
                    searchQuery = query,
                    selectedItem = selected,
                    discoverState = discover,
                    searchState = search,
                    libraryActionState = libraryAction,
                )
                is SearchState.Success -> CatalogScreenState.Success(
                    searchQuery = query,
                    selectedItem = selected,
                    discoverState = discover,
                    searchState = search,
                    libraryActionState = libraryAction,
                    searchResults = search.items,
                )
                is SearchState.Empty -> CatalogScreenState.Empty(
                    searchQuery = query,
                    selectedItem = selected,
                    discoverState = discover,
                    searchState = search,
                    libraryActionState = libraryAction,
                    message = "No results found for \"$query\"",
                )
                is SearchState.Error -> CatalogScreenState.Error(
                    searchQuery = query,
                    selectedItem = selected,
                    discoverState = discover,
                    searchState = search,
                    libraryActionState = libraryAction,
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
                    libraryActionState = libraryAction,
                )
                is DiscoverState.Success -> CatalogScreenState.Success(
                    searchQuery = query,
                    selectedItem = selected,
                    discoverState = discover,
                    searchState = search,
                    libraryActionState = libraryAction,
                    discoverFeed = discover.feed,
                )
                is DiscoverState.Degraded -> CatalogScreenState.Degraded(
                    searchQuery = query,
                    selectedItem = selected,
                    discoverState = discover,
                    searchState = search,
                    libraryActionState = libraryAction,
                    discoverFeed = discover.feed,
                    reason = "Some catalog sections could not be loaded",
                )
                is DiscoverState.Error -> CatalogScreenState.Error(
                    searchQuery = query,
                    selectedItem = selected,
                    discoverState = discover,
                    searchState = search,
                    libraryActionState = libraryAction,
                    error = discover.error,
                    message = discover.error.message ?: "Failed to load discover feed",
                )
            }
        }
    }
}
