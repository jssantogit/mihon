package eu.kanade.tachiyomi.ui.tsuzuki.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.tsuzuki.library.interactor.SetUnifiedLibraryCategories
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class CanonicalLibraryCategoriesViewModel(
    getCategories: GetCategories,
    private val setUnifiedLibraryCategories: SetUnifiedLibraryCategories,
) : ViewModel() {

    val categories = getCategories.subscribe().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList(),
    )

    fun setCategories(canonicalTitleId: String, categoryIds: List<Long>) {
        viewModelScope.launch {
            try {
                setUnifiedLibraryCategories.execute(canonicalTitleId, categoryIds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) {
                    "Failed to update categories for canonical library title $canonicalTitleId"
                }
            }
        }
    }
}
