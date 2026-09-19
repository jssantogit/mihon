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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tachiyomi.domain.tsuzuki.home.interactor.GetHomeCatalogFeed
import tachiyomi.domain.tsuzuki.home.interactor.ObserveHomeContinueReading
import tachiyomi.domain.tsuzuki.home.model.HomeCatalogFeed
import tachiyomi.domain.tsuzuki.home.model.HomeContinueReadingItem

@Immutable
data class TsuzukiHomeScreenState(
    val continueReading: List<HomeContinueReadingItem> = emptyList(),
    val catalogFeed: HomeCatalogFeed? = null,
    val isRefreshing: Boolean = false,
)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class TsuzukiHomeScreenModel(
    observeHomeContinueReading: ObserveHomeContinueReading,
    private val getHomeCatalogFeed: GetHomeCatalogFeed,
) : ViewModel() {

    private val catalogFeed = MutableStateFlow<HomeCatalogFeed?>(null)
    private val isRefreshing = MutableStateFlow(false)
    private var refreshJob: Job? = null

    val state: StateFlow<TsuzukiHomeScreenState> = combine(
        observeHomeContinueReading.subscribe(),
        catalogFeed,
        isRefreshing,
    ) { local, remote, refreshing ->
        TsuzukiHomeScreenState(
            continueReading = local,
            catalogFeed = remote,
            isRefreshing = refreshing,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = TsuzukiHomeScreenState(),
    )

    init {
        refresh()
    }

    fun refresh(): Job {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            isRefreshing.value = true
            try {
                catalogFeed.value = getHomeCatalogFeed.execute()
            } catch (error: CancellationException) {
                throw error
            } finally {
                isRefreshing.value = false
            }
        }
        return refreshJob!!
    }
}
