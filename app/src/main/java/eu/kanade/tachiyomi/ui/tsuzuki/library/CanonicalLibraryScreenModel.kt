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
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.tsuzuki.library.interactor.ObserveCanonicalLibrary
import tachiyomi.domain.tsuzuki.library.interactor.SetCanonicalLibraryStatus
import tachiyomi.domain.tsuzuki.migration.interactor.MigrateMihonLibraryToCanonical
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReadingStart
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import tachiyomi.domain.tsuzuki.reader.service.CanonicalReadingStartResolver

@Immutable
sealed interface CanonicalLibraryScreenState {
    data object Loading : CanonicalLibraryScreenState
    data class Success(
        val items: List<CanonicalLibraryCardModel>,
        val searchQuery: String? = null,
        val selectedCategoryId: Long? = null,
    ) : CanonicalLibraryScreenState
}

sealed interface CanonicalLibraryEvent {
    data class OpenReader(val canonicalChapterId: String) : CanonicalLibraryEvent
    data class OpenCanonicalTitle(val canonicalTitleId: String) : CanonicalLibraryEvent
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class CanonicalLibraryScreenModel(
    private val observeCanonicalLibrary: ObserveCanonicalLibrary,
    private val setCanonicalLibraryStatus: SetCanonicalLibraryStatus,
    private val removeUnifiedLibraryTitle: RemoveUnifiedLibraryTitle,
    private val migrateMihonLibraryToCanonical: MigrateMihonLibraryToCanonical,
    private val resolveCanonicalReadingStart: CanonicalReadingStartResolver,
    private val canonicalReadingRepository: CanonicalReadingRepository,
) : ViewModel() {

    private val eventChannel = Channel<CanonicalLibraryEvent>()
    val events = eventChannel.receiveAsFlow()

    private val searchQuery = MutableStateFlow<String?>(null)
    private val selectedCategoryId = MutableStateFlow<Long?>(null)

    val state: StateFlow<CanonicalLibraryScreenState> = flow<CanonicalLibraryScreenState> {
        try {
            migrateMihonLibraryToCanonical.execute()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Mihon library migration failed non-blockingly" }
        }

        val cards = observeCanonicalLibrary.subscribe()
            .flatMapLatest { libraryItems ->
                if (libraryItems.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(
                        libraryItems.map { item ->
                            canonicalReadingRepository
                                .observeProgressByCanonicalTitleId(item.id)
                                .map { progress -> item.toCardModel(progress) }
                        },
                    ) { values -> values.toList() }
                }
            }

        emitAll(
            combine(
                cards,
                searchQuery,
                selectedCategoryId,
            ) { items, query, categoryId ->
                val categoryFilteredItems = when (categoryId) {
                    null -> items
                    Category.UNCATEGORIZED_ID -> items.filter { it.categories.isEmpty() }
                    else -> items.filter { item ->
                        item.categories.any { it.id == categoryId }
                    }
                }
                val normalizedQuery = query?.trim().orEmpty()
                val filteredItems = if (normalizedQuery.isEmpty()) {
                    categoryFilteredItems
                } else {
                    categoryFilteredItems.filter {
                        it.title.contains(normalizedQuery, ignoreCase = true)
                    }
                }
                CanonicalLibraryScreenState.Success(
                    items = filteredItems,
                    searchQuery = query,
                    selectedCategoryId = categoryId,
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
        selectedCategoryId.value = categoryId
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
