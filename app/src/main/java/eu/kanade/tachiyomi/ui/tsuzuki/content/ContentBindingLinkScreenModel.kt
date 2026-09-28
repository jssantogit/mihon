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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.interactor.ConfirmContentBinding
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSearchFailureKind
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSearchMode
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSearchProgress
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSearchRequest
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSourceOutcome
import tachiyomi.domain.tsuzuki.content.interactor.ResolveContentBinding
import tachiyomi.domain.tsuzuki.content.repository.ContentPreferenceRepository
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreferences
import tachiyomi.domain.tsuzuki.source.model.ScoredSourceCandidate
import java.util.Locale

/** Persisted editions, not merely catalogue search hits. */
data class BindingRefreshRequest(
    val canonicalTitleId: String,
    val bindings: List<ContentBinding>,
) {
    init {
        require(bindings.isNotEmpty())
        require(bindings.all { it.canonicalTitleId == canonicalTitleId })
    }
}

sealed interface ContentBindingLinkState {
    data object Idle : ContentBindingLinkState
    data object Loading : ContentBindingLinkState
    data class Addons(val enabled: List<InstalledAddon>) : ContentBindingLinkState
    data class SearchResults(
        val addon: InstalledAddon,
        val isSearching: Boolean,
        /** Prior persisted rows are informational and never imply chapter readability. */
        val existingBindingCount: Int = 0,
        /** Bindings resolved by this search; not chapter options. */
        val boundCount: Int = 0,
        /** Only candidates that require explicit edition confirmation. */
        val confirmationCandidates: List<ScoredSourceCandidate> = emptyList(),
        val emptySourceCount: Int = 0,
        val noMatchSourceCount: Int = 0,
        val failureCount: Int = 0,
        val failureKinds: List<ContentBindingSearchFailureKind> = emptyList(),
        val queriedSourceIds: Set<Long> = emptySet(),
        val remainingSourceCount: Int = 0,
        val isConfirming: Boolean = false,
        val error: String? = null,
    ) : ContentBindingLinkState
    data class Error(val message: String) : ContentBindingLinkState
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ContentBindingLinkScreenModel(
    private val addonRepository: AddonRepository,
    private val resolveContentBinding: ResolveContentBinding,
    private val confirmContentBinding: ConfirmContentBinding,
    private val contentPreferenceRepository: ContentPreferenceRepository,
    private val readerPreferences: CanonicalReaderPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow<ContentBindingLinkState>(ContentBindingLinkState.Idle)
    val state: StateFlow<ContentBindingLinkState> = _state.asStateFlow()

    private val _bindingChanges = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Legacy title-level event for integrations that have not adopted scoped refreshes. */
    val bindingChanges: SharedFlow<String> = _bindingChanges.asSharedFlow()

    private val _bindingUpdates = MutableSharedFlow<BindingRefreshRequest>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Concrete materialized bindings permit a single-edition refresh after manual link. */
    val bindingUpdates: SharedFlow<BindingRefreshRequest> = _bindingUpdates.asSharedFlow()

    private var titleId: String? = null
    private var enabledAddons = emptyList<InstalledAddon>()
    private var searchOperation: Job? = null
    private var confirmationOperation: Job? = null
    private var generation = 0L
    private val pendingBindings = linkedMapOf<String, ContentBinding>()

    fun start(canonicalTitleId: String) {
        searchOperation?.cancel()
        confirmationOperation?.cancel()
        pendingBindings.clear()
        val currentGeneration = ++generation
        titleId = canonicalTitleId
        _state.value = ContentBindingLinkState.Loading
        searchOperation = viewModelScope.launch {
            try {
                val installed = addonRepository.snapshot()
                if (currentGeneration != generation) return@launch
                // Package ID is the user-visible Add-on identity. A multi-source extension stays
                // one row; the internal source IDs remain owned by the resolver.
                enabledAddons = installed
                    .filter { it.enabled && it.mihonSourceIds.isNotEmpty() }
                    .distinctBy { it.id }
                    .sortedBy { it.displayName }
                _state.value = ContentBindingLinkState.Addons(enabledAddons)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                if (currentGeneration == generation) {
                    _state.value = ContentBindingLinkState.Error("Unable to list installed reading Add-ons.")
                }
            }
        }
    }

    fun selectAddon(addonId: AddonId) {
        val title = titleId ?: return
        val addon = enabledAddons.firstOrNull { it.id == addonId && it.enabled } ?: return
        publishPendingBindingRefresh()
        searchOperation?.cancel()
        confirmationOperation?.cancel()
        val currentGeneration = ++generation
        _state.value = ContentBindingLinkState.SearchResults(addon = addon, isSearching = true)
        searchOperation = viewModelScope.launch {
            runSearch(
                generation = currentGeneration,
                titleId = title,
                addon = addon,
                mode = ContentBindingSearchMode.INITIAL,
                alreadyQueriedSourceIds = emptySet(),
            )
        }
    }

    /** Starts the next explicit bounded batch, never repeating IDs queried in earlier batches. */
    fun searchMore() {
        val current = _state.value as? ContentBindingLinkState.SearchResults ?: return
        if (current.isSearching || current.isConfirming || current.remainingSourceCount <= 0) return
        val title = titleId ?: return
        searchOperation?.cancel()
        val currentGeneration = ++generation
        _state.value = current.copy(isSearching = true, error = null)
        searchOperation = viewModelScope.launch {
            runSearch(
                generation = currentGeneration,
                titleId = title,
                addon = current.addon,
                mode = ContentBindingSearchMode.BROADEN,
                alreadyQueriedSourceIds = current.queriedSourceIds,
            )
        }
    }

    private suspend fun runSearch(
        generation: Long,
        titleId: String,
        addon: InstalledAddon,
        mode: ContentBindingSearchMode,
        alreadyQueriedSourceIds: Set<Long>,
    ) {
        try {
            val titlePreference = contentPreferenceRepository.get(titleId)?.preferredLanguage
            val preferredLanguages = preferredSourceLanguages(
                titlePreference = titlePreference,
                configuredLanguages = readerPreferences.preferredLanguages.get(),
                locale = Locale.getDefault(),
            )
            val request = ContentBindingSearchRequest(
                canonicalTitleId = titleId,
                addonId = addon.id,
                preferredLanguages = preferredLanguages,
                mode = mode,
                alreadyQueriedSourceIds = alreadyQueriedSourceIds,
            )
            resolveContentBinding.searchProgress(request).collect { event ->
                if (generation != this@ContentBindingLinkScreenModel.generation) return@collect
                when (event) {
                    is ContentBindingSearchProgress.ExistingBindingsObserved -> {
                        updateSearch(generation) { it.copy(existingBindingCount = event.bindingCount) }
                    }
                    is ContentBindingSearchProgress.SourceCompleted -> {
                        updateSearch(generation) { current ->
                            when (event.outcome) {
                                ContentBindingSourceOutcome.BOUND -> current.copy(
                                    boundCount = current.boundCount + event.bindings.size,
                                )
                                ContentBindingSourceOutcome.CONFIRMATION_REQUIRED -> current.copy(
                                    confirmationCandidates = mergeCandidates(
                                        current.confirmationCandidates,
                                        event.candidates,
                                    ),
                                )
                                ContentBindingSourceOutcome.EMPTY -> current.copy(
                                    emptySourceCount = current.emptySourceCount + 1,
                                )
                                ContentBindingSourceOutcome.NO_MATCH -> current.copy(
                                    noMatchSourceCount = current.noMatchSourceCount + 1,
                                )
                                ContentBindingSourceOutcome.FAILURE -> current.copy(
                                    failureCount = current.failureCount + 1,
                                    failureKinds = event.failure?.kind?.let(current.failureKinds::plus)
                                        ?: current.failureKinds,
                                )
                            }
                        }
                        if (event.outcome == ContentBindingSourceOutcome.BOUND) {
                            event.bindings.forEach { persisted -> pendingBindings[persisted.id] = persisted }
                        }
                    }
                    is ContentBindingSearchProgress.Completed -> {
                        updateSearch(generation) { current ->
                            current.copy(
                                isSearching = false,
                                queriedSourceIds = current.queriedSourceIds + event.queriedSourceIds,
                                remainingSourceCount = event.remainingSourceCount,
                            )
                        }
                        publishPendingBindingRefresh()
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            if (generation == this@ContentBindingLinkScreenModel.generation) {
                publishPendingBindingRefresh()
                updateSearch(generation) {
                    it.copy(
                        isSearching = false,
                        error = "Unable to finish this search. You can retry or choose another Add-on.",
                    )
                }
            }
        }
    }

    fun confirm(candidate: ScoredSourceCandidate) {
        val title = titleId ?: return
        val current = _state.value as? ContentBindingLinkState.SearchResults ?: return
        val selected = current.confirmationCandidates.firstOrNull {
            it.candidate.sourceId == candidate.candidate.sourceId &&
                it.candidate.sourceUrl == candidate.candidate.sourceUrl
        } ?: return
        if (current.isConfirming) return
        confirmationOperation?.cancel()
        val currentGeneration = generation
        _state.value = current.copy(isConfirming = true, error = null)
        confirmationOperation = viewModelScope.launch {
            try {
                val result = confirmContentBinding.execute(title, current.addon.id, selected)
                if (currentGeneration != generation) return@launch
                val latest = _state.value as? ContentBindingLinkState.SearchResults ?: return@launch
                _state.value = if (result.isSuccess) {
                    _bindingChanges.tryEmit(title)
                    result.getOrNull()?.let { binding ->
                        _bindingUpdates.tryEmit(
                            BindingRefreshRequest(title, listOf(binding)),
                        )
                    }
                    latest.copy(
                        isConfirming = false,
                        boundCount = latest.boundCount + 1,
                        confirmationCandidates = latest.confirmationCandidates.filterNot {
                            it.candidate.sourceId == selected.candidate.sourceId &&
                                it.candidate.sourceUrl == selected.candidate.sourceUrl
                        },
                    )
                } else {
                    latest.copy(
                        isConfirming = false,
                        error = "Unable to save this reading source. Please retry.",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                if (currentGeneration == generation) {
                    val latest = _state.value as? ContentBindingLinkState.SearchResults ?: return@launch
                    _state.value = latest.copy(
                        isConfirming = false,
                        error = "Unable to save this reading source. Please retry.",
                    )
                }
            }
        }
    }

    fun backToAddons() {
        publishPendingBindingRefresh()
        searchOperation?.cancel()
        confirmationOperation?.cancel()
        ++generation
        _state.value = ContentBindingLinkState.Addons(enabledAddons)
    }

    fun close() {
        publishPendingBindingRefresh()
        searchOperation?.cancel()
        confirmationOperation?.cancel()
        ++generation
        titleId = null
        _state.value = ContentBindingLinkState.Idle
    }

    private fun publishPendingBindingRefresh() {
        if (pendingBindings.isEmpty()) return
        val title = titleId ?: return
        val bindings = pendingBindings.values.filter { it.canonicalTitleId == title }
        pendingBindings.clear()
        if (bindings.isEmpty()) return
        _bindingChanges.tryEmit(title)
        _bindingUpdates.tryEmit(BindingRefreshRequest(title, bindings))
    }

    private fun updateSearch(
        expectedGeneration: Long,
        transform: (ContentBindingLinkState.SearchResults) -> ContentBindingLinkState.SearchResults,
    ) {
        if (expectedGeneration != generation) return
        val current = _state.value as? ContentBindingLinkState.SearchResults ?: return
        _state.value = transform(current)
    }

    private fun mergeCandidates(
        existing: List<ScoredSourceCandidate>,
        incoming: List<ScoredSourceCandidate>,
    ): List<ScoredSourceCandidate> {
        val seen = existing.mapTo(mutableSetOf()) { it.candidate.sourceId to it.candidate.sourceUrl }
        return existing + incoming.filter { seen.add(it.candidate.sourceId to it.candidate.sourceUrl) }
    }
}

/** Use explicit reader preferences when present; otherwise avoid arbitrarily scanning alphabetic internal IDs. */
internal fun preferredSourceLanguages(
    titlePreference: String?,
    configuredLanguages: List<String>,
    locale: Locale,
): List<String> {
    val configured = listOfNotNull(titlePreference) + configuredLanguages
    val languages = configured.takeIf(List<String>::isNotEmpty) ?: listOf(
        locale.toLanguageTag(),
        locale.language,
        "en",
    )
    return languages.map(String::trim)
        .filter { it.isNotEmpty() && !it.equals("und", ignoreCase = true) }
        .distinctBy { it.lowercase(Locale.ROOT) }
}
