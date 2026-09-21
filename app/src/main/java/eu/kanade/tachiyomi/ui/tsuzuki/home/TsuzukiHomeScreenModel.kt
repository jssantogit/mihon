package eu.kanade.tachiyomi.ui.tsuzuki.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.tsuzuki.home.interactor.GetConfiguredHomeSections
import tachiyomi.domain.tsuzuki.home.interactor.ObserveHomeContinueReading
import tachiyomi.domain.tsuzuki.home.model.HomeContinueReadingItem
import tachiyomi.domain.tsuzuki.home.model.HomeSection
import tachiyomi.domain.tsuzuki.home.repository.ContinueReadingVisibilityRepository
import tachiyomi.domain.tsuzuki.library.interactor.ObserveCanonicalLibrary
import tachiyomi.domain.tsuzuki.reader.interactor.ImportLegacyCanonicalProgress
import kotlin.time.Clock

@Immutable
data class TsuzukiHomeScreenState(
    val continueReading: List<HomeContinueReadingItem> = emptyList(),
    val sections: List<HomeSection> = emptyList(),
)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class TsuzukiHomeScreenModel(
    observeHomeContinueReading: ObserveHomeContinueReading,
    getConfiguredHomeSections: GetConfiguredHomeSections,
    private val visibilityRepository: ContinueReadingVisibilityRepository,
    private val observeCanonicalLibrary: ObserveCanonicalLibrary,
    private val historyRepository: HistoryRepository,
    private val importLegacyCanonicalProgress: ImportLegacyCanonicalProgress,
) : ViewModel() {

    val state: StateFlow<TsuzukiHomeScreenState> = combine(
        observeHomeContinueReading.subscribe(),
        getConfiguredHomeSections.subscribe(),
    ) { continueReading, sections ->
        TsuzukiHomeScreenState(
            continueReading = continueReading,
            sections = sections,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = TsuzukiHomeScreenState(),
    )

    init {
        viewModelScope.launch {
            combine(
                observeCanonicalLibrary.subscribe(),
                historyRepository.getHistory(""),
            ) { libraryItems, _ ->
                libraryItems
            }.collectLatest { libraryItems ->
                for (item in libraryItems) {
                    try {
                        importLegacyCanonicalProgress.execute(item.title.id)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        // Legacy local compatibility must never block Home.
                    }
                }
            }
        }
    }

    fun removeFromContinueReading(item: HomeContinueReadingItem) {
        viewModelScope.launch {
            visibilityRepository.hide(
                canonicalTitleId = item.canonicalTitleId,
                hiddenAt = maxOf(
                    item.updatedAt,
                    Clock.System.now().toEpochMilliseconds(),
                ),
            )
        }
    }
}
