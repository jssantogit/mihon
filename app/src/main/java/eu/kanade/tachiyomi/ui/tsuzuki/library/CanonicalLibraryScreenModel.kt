package eu.kanade.tachiyomi.ui.tsuzuki.library

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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.tsuzuki.library.interactor.ObserveCanonicalLibrary
import tachiyomi.domain.tsuzuki.library.interactor.RemoveCanonicalLibraryItem
import tachiyomi.domain.tsuzuki.library.interactor.SetCanonicalLibraryStatus
import tachiyomi.domain.tsuzuki.library.model.CanonicalLibraryItem
import tachiyomi.domain.tsuzuki.migration.interactor.MigrateMihonLibraryToCanonical
import tachiyomi.domain.tsuzuki.model.LibraryStatus

@Immutable
sealed interface CanonicalLibraryScreenState {
    data object Loading : CanonicalLibraryScreenState
    data class Success(val items: List<CanonicalLibraryItem>) : CanonicalLibraryScreenState
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class CanonicalLibraryScreenModel(
    private val observeCanonicalLibrary: ObserveCanonicalLibrary,
    private val setCanonicalLibraryStatus: SetCanonicalLibraryStatus,
    private val removeCanonicalLibraryItem: RemoveCanonicalLibraryItem,
    private val migrateMihonLibraryToCanonical: MigrateMihonLibraryToCanonical,
) : ViewModel() {

    val state: StateFlow<CanonicalLibraryScreenState> = flow<CanonicalLibraryScreenState> {
        try {
            migrateMihonLibraryToCanonical.execute()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Mihon library migration failed non-blockingly" }
        }

        emitAll(
            observeCanonicalLibrary.subscribe()
                .map { items -> CanonicalLibraryScreenState.Success(items) },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = CanonicalLibraryScreenState.Loading,
    )

    fun setStatus(canonicalTitleId: String, status: LibraryStatus) {
        viewModelScope.launch {
            setCanonicalLibraryStatus.execute(canonicalTitleId, status)
        }
    }

    fun removeItem(canonicalTitleId: String) {
        viewModelScope.launch {
            removeCanonicalLibraryItem.execute(canonicalTitleId)
        }
    }
}
