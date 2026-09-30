package eu.kanade.tachiyomi.ui.tsuzuki.library

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.tsuzuki.library.interactor.RemoveUnifiedLibraryTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.library.interactor.ObserveCanonicalLibrary
import tachiyomi.domain.tsuzuki.library.interactor.ObserveUnifiedLibrary
import tachiyomi.domain.tsuzuki.library.interactor.RefreshUserLibraries
import tachiyomi.domain.tsuzuki.library.interactor.SetCanonicalLibraryStatus
import tachiyomi.domain.tsuzuki.library.model.LibraryTitle
import tachiyomi.domain.tsuzuki.library.model.UnifiedLibraryTitle
import tachiyomi.domain.tsuzuki.migration.interactor.MigrateMihonLibraryToCanonical
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReadingStart
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import tachiyomi.domain.tsuzuki.reader.service.CanonicalReadingStartResolver
import tachiyomi.domain.tsuzuki.source.interactor.ResolveCanonicalSourceManga

@Immutable
sealed interface CanonicalLibraryScreenState {
    data object Loading : CanonicalLibraryScreenState

    data class Success(
        val items: List<CanonicalLibraryCardModel>,
        val searchQuery: String? = null,
        val selectedCategoryId: Long? = null,
        val filters: CanonicalLibraryFilterState = CanonicalLibraryFilterState(),
        val availableOrigins: Set<String> = emptySet(),
        val availableFormats: Set<CatalogItemFormat> = emptySet(),
        val availableProviderLists: List<CanonicalLibraryListFilterOption> = emptyList(),
    ) : CanonicalLibraryScreenState
}

sealed interface CanonicalLibraryEvent {
    data class OpenReader(val canonicalChapterId: String) : CanonicalLibraryEvent
    data class OpenCanonicalTitle(val canonicalTitleId: String) : CanonicalLibraryEvent
}

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class CanonicalLibraryScreenModel private constructor(
    private val observeLibrary: () -> Flow<List<UnifiedLibraryTitle>>,
    private val setCanonicalLibraryStatus: SetCanonicalLibraryStatus,
    private val removeUnifiedLibraryTitle: RemoveUnifiedLibraryTitle,
    private val migrateMihonLibraryToCanonical: MigrateMihonLibraryToCanonical,
    private val resolveCanonicalReadingStart: CanonicalReadingStartResolver,
    private val canonicalReadingRepository: CanonicalReadingRepository,
    private val refreshUserLibrariesAction: (suspend () -> Unit)?,
    private val mangaRepository: MangaRepository? = null,
    private val resolveCanonicalSourceManga: ResolveCanonicalSourceManga? = null,
) : ViewModel() {

    @Inject
    constructor(
        observeUnifiedLibrary: ObserveUnifiedLibrary,
        setCanonicalLibraryStatus: SetCanonicalLibraryStatus,
        removeUnifiedLibraryTitle: RemoveUnifiedLibraryTitle,
        migrateMihonLibraryToCanonical: MigrateMihonLibraryToCanonical,
        resolveCanonicalReadingStart: CanonicalReadingStartResolver,
        canonicalReadingRepository: CanonicalReadingRepository,
        refreshUserLibraries: RefreshUserLibraries,
        mangaRepository: MangaRepository,
        resolveCanonicalSourceManga: ResolveCanonicalSourceManga,
    ) : this(
        observeLibrary = observeUnifiedLibrary::subscribe,
        setCanonicalLibraryStatus = setCanonicalLibraryStatus,
        removeUnifiedLibraryTitle = removeUnifiedLibraryTitle,
        migrateMihonLibraryToCanonical = migrateMihonLibraryToCanonical,
        resolveCanonicalReadingStart = resolveCanonicalReadingStart,
        canonicalReadingRepository = canonicalReadingRepository,
        refreshUserLibrariesAction = { refreshUserLibraries.refreshConnected() },
        mangaRepository = mangaRepository,
        resolveCanonicalSourceManga = resolveCanonicalSourceManga,
    )

    internal constructor(
        observeCanonicalLibrary: ObserveCanonicalLibrary,
        setCanonicalLibraryStatus: SetCanonicalLibraryStatus,
        removeUnifiedLibraryTitle: RemoveUnifiedLibraryTitle,
        migrateMihonLibraryToCanonical: MigrateMihonLibraryToCanonical,
        resolveCanonicalReadingStart: CanonicalReadingStartResolver,
        canonicalReadingRepository: CanonicalReadingRepository,
    ) : this(
        observeLibrary = {
            observeCanonicalLibrary.subscribe().map { items ->
                items.map(LibraryTitle::toLocalUnifiedTitle)
            }
        },
        setCanonicalLibraryStatus = setCanonicalLibraryStatus,
        removeUnifiedLibraryTitle = removeUnifiedLibraryTitle,
        migrateMihonLibraryToCanonical = migrateMihonLibraryToCanonical,
        resolveCanonicalReadingStart = resolveCanonicalReadingStart,
        canonicalReadingRepository = canonicalReadingRepository,
        refreshUserLibrariesAction = null,
        mangaRepository = null,
    )

    private val eventChannel = Channel<CanonicalLibraryEvent>()
    val events = eventChannel.receiveAsFlow()

    private val searchQuery = MutableStateFlow<String?>(null)
    private val filters = MutableStateFlow(CanonicalLibraryFilterState())

    init {
        refreshUserLibrariesAction?.let { refresh ->
            viewModelScope.launch {
                try {
                    refresh()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logcat(LogPriority.WARN, error) {
                        "Failed to refresh connected user libraries non-blockingly"
                    }
                }
            }
        }
    }

    val state: StateFlow<CanonicalLibraryScreenState> = flow<CanonicalLibraryScreenState> {
        try {
            migrateMihonLibraryToCanonical.execute()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Mihon library migration failed non-blockingly" }
        }

        val localCoverUrls = mangaRepository
            ?.getLibraryMangaAsFlow()
            ?.map { libraryManga ->
                libraryManga.associate { item -> item.id to item.manga.thumbnailUrl }
            }
            ?: flowOf(emptyMap<Long, String?>())

        val cards = combine(
            observeLibrary(),
            localCoverUrls,
        ) { libraryItems, covers ->
            libraryItems to covers
        }.flatMapLatest { (libraryItems, covers) ->
            if (libraryItems.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    libraryItems.map { item ->
                        canonicalReadingRepository
                            .observeProgressByCanonicalTitleId(item.id)
                            .map { progress ->
                                val sourceCover = try {
                                    resolveCanonicalSourceManga
                                        ?.execute(item.id)
                                        ?.asMangaCover()
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Throwable) {
                                    null
                                }
                                val providerCover = item.preferredExternalCoverUrl()
                                val localCover = item.localCoverUrl(covers)
                                logcat {
                                    "TsuzukiCover library title=${item.id.take(8)} " +
                                        "provider=${!providerCover.isNullOrBlank()} " +
                                        "source=${sourceCover != null && !sourceCover.url.isNullOrBlank()} " +
                                        "local=${!localCover.isNullOrBlank()}"
                                }
                                item.toCardModel(
                                    progress = progress,
                                    localCoverUrl = localCover,
                                    sourceCover = sourceCover,
                                )
                            }
                    },
                ) { values -> values.toList() }
            }
        }

        emitAll(
            combine(
                cards,
                searchQuery,
                filters,
            ) { items, query, filterState ->
                val filteredByFacets = filterCanonicalLibraryCards(items, filterState)
                val normalizedQuery = query?.trim().orEmpty()
                val visibleItems = if (normalizedQuery.isEmpty()) {
                    filteredByFacets
                } else {
                    filteredByFacets.filter {
                        it.title.contains(normalizedQuery, ignoreCase = true)
                    }
                }

                CanonicalLibraryScreenState.Success(
                    items = visibleItems,
                    searchQuery = query,
                    selectedCategoryId = filterState.categoryId,
                    filters = filterState,
                    availableOrigins = items.flatMapTo(linkedSetOf()) { it.origins },
                    availableFormats = items.mapNotNullTo(linkedSetOf()) { item ->
                        item.format.takeUnless { it == CatalogItemFormat.UNKNOWN }
                    },
                    availableProviderLists = availableProviderListFilters(
                        items = items,
                        origin = filterState.origin,
                    ),
                )
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = CanonicalLibraryScreenState.Loading,
    )

    fun search(query: String?) {
        searchQuery.value = query
    }

    fun selectCategory(categoryId: Long?) {
        filters.update { it.copy(categoryId = categoryId) }
    }

    fun selectStatus(status: LibraryStatus?) {
        filters.update { it.copy(status = status) }
    }

    fun selectOrigin(origin: String?) {
        filters.update { current ->
            current.copy(
                origin = origin,
                listKey = current.listKey.takeIf { origin == current.origin },
            )
        }
    }

    fun selectProviderList(listKey: String?) {
        filters.update { it.copy(listKey = listKey) }
    }

    fun toggleFormat(format: CatalogItemFormat) {
        filters.update { current ->
            val formats = current.formats.toMutableSet()
            if (!formats.add(format)) {
                formats.remove(format)
            }
            current.copy(formats = formats)
        }
    }

    fun clearAdvancedFilters() {
        filters.update {
            it.copy(
                origin = null,
                listKey = null,
                formats = emptySet(),
                categoryId = null,
            )
        }
    }

    fun setStatus(canonicalTitleId: String, status: LibraryStatus) {
        viewModelScope.launch {
            setCanonicalLibraryStatus.execute(canonicalTitleId, status)
        }
    }

    fun removeItem(canonicalTitleId: String) {
        viewModelScope.launch {
            try {
                if (!removeUnifiedLibraryTitle.execute(canonicalTitleId)) {
                    logcat(LogPriority.WARN) {
                        "Failed to clear Mihon projections for canonical library title $canonicalTitleId"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) {
                    "Failed to remove canonical library title $canonicalTitleId"
                }
            }
        }
    }

    fun refreshUserLibraries() {
        val refresh = refreshUserLibrariesAction ?: return
        viewModelScope.launch {
            try {
                refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logcat(LogPriority.WARN, error) { "Failed to refresh connected user libraries" }
            }
        }
    }

    fun readOrContinue(canonicalTitleId: String) {
        viewModelScope.launch {
            when (val result = resolveCanonicalReadingStart.execute(canonicalTitleId)) {
                is CanonicalReadingStart.Ready -> {
                    eventChannel.send(CanonicalLibraryEvent.OpenReader(result.canonicalChapterId))
                }
                is CanonicalReadingStart.Unavailable -> {
                    eventChannel.send(
                        CanonicalLibraryEvent.OpenCanonicalTitle(canonicalTitleId),
                    )
                }
            }
        }
    }
}

private fun LibraryTitle.toLocalUnifiedTitle() = UnifiedLibraryTitle(
    title = title,
    localEntry = entry,
    externalMemberships = emptyList(),
    sources = sources,
    categories = categories,
    format = CatalogItemFormat.UNKNOWN,
)

private fun UnifiedLibraryTitle.localCoverUrl(
    covers: Map<Long, String?>,
): String? = sources
    .asSequence()
    .sortedByDescending { it.preferredOverride }
    .mapNotNull { it.mihonMangaId }
    .mapNotNull(covers::get)
    .firstOrNull()
