package eu.kanade.tachiyomi.ui.tsuzuki.content

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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
import tachiyomi.domain.tsuzuki.content.interactor.ConfirmContentBinding
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingConfirmationRequiredException
import tachiyomi.domain.tsuzuki.content.interactor.ResolveContentBinding
import tachiyomi.domain.tsuzuki.source.model.ScoredSourceCandidate

sealed interface ContentBindingLinkState {
    data object Idle : ContentBindingLinkState
    data object Loading : ContentBindingLinkState
    data class Addons(val enabled: List<InstalledAddon>) : ContentBindingLinkState
    data class Searching(val addonName: String) : ContentBindingLinkState
    data class Candidates(
        val addon: InstalledAddon,
        val candidates: List<ScoredSourceCandidate>,
    ) : ContentBindingLinkState
    data class Linked(val addonName: String) : ContentBindingLinkState
    data class Error(val message: String) : ContentBindingLinkState
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ContentBindingLinkScreenModel(
    private val addonRepository: AddonRepository,
    private val resolveContentBinding: ResolveContentBinding,
    private val confirmContentBinding: ConfirmContentBinding,
) : ViewModel() {
    private val _state = MutableStateFlow<ContentBindingLinkState>(ContentBindingLinkState.Idle)
    val state: StateFlow<ContentBindingLinkState> = _state.asStateFlow()

    private var titleId: String? = null
    private var enabledAddons = emptyList<InstalledAddon>()
    private var operation: Job? = null

    fun start(canonicalTitleId: String) {
        operation?.cancel()
        titleId = canonicalTitleId
        _state.value = ContentBindingLinkState.Loading
        operation = viewModelScope.launch {
            try {
                enabledAddons = addonRepository.snapshot()
                    .filter { it.enabled && it.mihonSourceIds.isNotEmpty() }
                    .sortedBy { it.displayName }
                _state.value = ContentBindingLinkState.Addons(enabledAddons)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                _state.value = ContentBindingLinkState.Error("Unable to list installed reading Add-ons.")
            }
        }
    }

    fun selectAddon(addonId: AddonId) {
        val title = titleId ?: return
        val addon = enabledAddons.firstOrNull { it.id == addonId } ?: return
        operation?.cancel()
        _state.value = ContentBindingLinkState.Searching(addon.displayName)
        operation = viewModelScope.launch {
            try {
                val result = resolveContentBinding.executeAll(title, addonId)
                val failure = result.exceptionOrNull()
                when {
                    failure is ContentBindingConfirmationRequiredException -> {
                        _state.value = ContentBindingLinkState.Candidates(addon, failure.candidates)
                    }
                    failure != null -> {
                        _state.value = ContentBindingLinkState.Error(
                            "Could not link this Add-on. Its search may be unavailable or have no matching title.",
                        )
                    }
                    result.getOrThrow().isNotEmpty() -> {
                        _state.value = ContentBindingLinkState.Linked(addon.displayName)
                    }
                    else -> {
                        _state.value = ContentBindingLinkState.Error("No matching title was found in this Add-on.")
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                _state.value = ContentBindingLinkState.Error("Unable to search this Add-on.")
            }
        }
    }

    fun confirm(candidate: ScoredSourceCandidate) {
        val title = titleId ?: return
        val candidates = _state.value as? ContentBindingLinkState.Candidates ?: return
        val selected = candidates.candidates.firstOrNull {
            it.candidate.sourceId == candidate.candidate.sourceId &&
                it.candidate.sourceUrl == candidate.candidate.sourceUrl
        } ?: return
        operation?.cancel()
        _state.value = ContentBindingLinkState.Searching(candidates.addon.displayName)
        operation = viewModelScope.launch {
            val result = confirmContentBinding.execute(title, candidates.addon.id, selected)
            _state.value = if (result.isSuccess) {
                ContentBindingLinkState.Linked(candidates.addon.displayName)
            } else {
                ContentBindingLinkState.Error("Unable to save this reading source. Please retry.")
            }
        }
    }

    fun backToAddons() {
        operation?.cancel()
        _state.value = ContentBindingLinkState.Addons(enabledAddons)
    }

    fun close() {
        operation?.cancel()
        titleId = null
        _state.value = ContentBindingLinkState.Idle
    }
}
