package eu.kanade.tachiyomi.ui.tsuzuki.source

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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import tachiyomi.domain.tsuzuki.source.interactor.ConfirmSourceMapping
import tachiyomi.domain.tsuzuki.source.interactor.GetPreferredReadingSources
import tachiyomi.domain.tsuzuki.source.interactor.ResolveReadingSource
import tachiyomi.domain.tsuzuki.source.interactor.SetTitleSourceOverride
import tachiyomi.domain.tsuzuki.source.model.ScoredSourceCandidate
import tachiyomi.domain.tsuzuki.source.model.SourceResolutionResult

@Immutable
sealed interface SourceResolverScreenState {
    data object Loading : SourceResolverScreenState

    data class SelectLanguage(
        val title: String,
        val languages: List<String>,
    ) : SourceResolverScreenState

    data class Searching(
        val title: String,
        val language: String,
        val broaden: Boolean,
    ) : SourceResolverScreenState

    data class Resolved(
        val title: String,
        val mapping: SourceTitleMapping,
        val mappings: List<SourceTitleMapping>,
        val reused: Boolean,
    ) : SourceResolverScreenState

    data class NeedsConfirmation(
        val title: String,
        val candidates: List<ScoredSourceCandidate>,
    ) : SourceResolverScreenState

    data class NoPreferredSources(
        val title: String,
        val language: String,
    ) : SourceResolverScreenState

    data class NotFound(
        val title: String,
        val language: String,
        val searchedSourceIds: List<Long>,
        val canBroaden: Boolean,
    ) : SourceResolverScreenState

    data class Conflict(
        val title: String,
        val existingCanonicalTitleId: String,
    ) : SourceResolverScreenState

    data class Error(
        val title: String,
        val error: Throwable,
    ) : SourceResolverScreenState
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class SourceResolverScreenModel(
    private val getPreferredReadingSources: GetPreferredReadingSources,
    private val resolveReadingSource: ResolveReadingSource,
    private val confirmSourceMapping: ConfirmSourceMapping,
    private val setTitleSourceOverride: SetTitleSourceOverride,
    private val sourceTitleMappingRepository: SourceTitleMappingRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<SourceResolverScreenState>(SourceResolverScreenState.Loading)
    val state: StateFlow<SourceResolverScreenState> = _state.asStateFlow()

    private var canonicalTitleId: String? = null
    private var title: String = ""
    private var selectedLanguage: String? = null
    private var operation: Job? = null

    fun start(canonicalTitleId: String, title: String): Job {
        if (this.canonicalTitleId == canonicalTitleId && this.title == title && operation?.isActive == true) {
            return operation!!
        }
        this.canonicalTitleId = canonicalTitleId
        this.title = title
        operation?.cancel()
        _state.value = SourceResolverScreenState.Loading
        operation = viewModelScope.launch { initialize(canonicalTitleId, title) }
        return operation!!
    }

    fun selectLanguage(language: String): Job? {
        val canonicalTitleId = canonicalTitleId ?: return null
        val normalized = language.trim()
        if (normalized.isEmpty()) return null
        selectedLanguage = normalized
        return resolve(canonicalTitleId, title, normalized, broaden = false)
    }

    fun checkMoreSources(): Job? {
        val current = _state.value as? SourceResolverScreenState.NotFound ?: return null
        if (!current.canBroaden) return null
        val canonicalTitleId = canonicalTitleId ?: return null
        return resolve(canonicalTitleId, title, current.language, broaden = true)
    }

    fun confirm(candidate: ScoredSourceCandidate): Job? {
        val canonicalTitleId = canonicalTitleId ?: return null
        operation?.cancel()
        _state.value = SourceResolverScreenState.Searching(title, candidate.candidate.language, broaden = false)
        operation = viewModelScope.launch {
            try {
                val result = confirmSourceMapping.execute(
                    canonicalTitleId = canonicalTitleId,
                    candidate = candidate.candidate,
                    matchConfidence = candidate.confidence,
                    verifiedByUser = true,
                )
                publishResult(result, title, candidate.candidate.language)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = SourceResolverScreenState.Error(title, e)
            }
        }
        return operation!!
    }

    fun setTitleSourceOverride(mappingId: String?): Job? {
        val canonicalTitleId = canonicalTitleId ?: return null
        operation?.cancel()
        operation = viewModelScope.launch {
            try {
                setTitleSourceOverride.execute(canonicalTitleId, mappingId)
                val mappings = sourceTitleMappingRepository.getByCanonicalTitleId(canonicalTitleId)
                val preferred = mappings.firstOrNull { it.preferredOverride } ?: mappings.firstOrNull()
                if (preferred == null) {
                    initialize(canonicalTitleId, title)
                } else {
                    _state.value = SourceResolverScreenState.Resolved(
                        title = title,
                        mapping = preferred,
                        mappings = mappings,
                        reused = true,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = SourceResolverScreenState.Error(title, e)
            }
        }
        return operation!!
    }

    fun retry(): Job? {
        val canonicalTitleId = canonicalTitleId ?: return null
        return start(canonicalTitleId, title)
    }

    private suspend fun initialize(canonicalTitleId: String, title: String) {
        try {
            val mappings = sourceTitleMappingRepository.getByCanonicalTitleId(canonicalTitleId)
            val preferred = mappings.firstOrNull { it.preferredOverride } ?: mappings.firstOrNull()
            if (preferred != null) {
                _state.value = SourceResolverScreenState.Resolved(
                    title = title,
                    mapping = preferred,
                    mappings = mappings,
                    reused = true,
                )
                return
            }

            val languages = getPreferredReadingSources.getConfiguredLanguages().distinct().sorted()
            when (languages.size) {
                0 -> {
                    selectedLanguage = ""
                    _state.value = SourceResolverScreenState.NoPreferredSources(title, "")
                }
                1 -> {
                    selectedLanguage = languages.single()
                    resolveNow(canonicalTitleId, title, languages.single(), broaden = false)
                }
                else -> _state.value = SourceResolverScreenState.SelectLanguage(title, languages)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _state.value = SourceResolverScreenState.Error(title, e)
        }
    }

    private fun resolve(
        canonicalTitleId: String,
        title: String,
        language: String,
        broaden: Boolean,
    ): Job {
        operation?.cancel()
        selectedLanguage = language
        _state.value = SourceResolverScreenState.Searching(title, language, broaden)
        operation = viewModelScope.launch {
            resolveNow(canonicalTitleId, title, language, broaden)
        }
        return operation!!
    }

    private suspend fun resolveNow(
        canonicalTitleId: String,
        title: String,
        language: String,
        broaden: Boolean,
    ) {
        try {
            val result = resolveReadingSource.execute(canonicalTitleId, language, broaden)
            publishResult(result, title, language)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _state.value = SourceResolverScreenState.Error(title, e)
        }
    }

    private suspend fun publishResult(
        result: SourceResolutionResult,
        title: String,
        language: String,
    ) {
        when (result) {
            is SourceResolutionResult.Resolved -> {
                val mappings = sourceTitleMappingRepository.getByCanonicalTitleId(result.mapping.canonicalTitleId)
                _state.value = SourceResolverScreenState.Resolved(
                    title = title,
                    mapping = result.mapping,
                    mappings = mappings.ifEmpty { listOf(result.mapping) },
                    reused = result.reused,
                )
            }
            is SourceResolutionResult.NeedsConfirmation -> {
                _state.value = SourceResolverScreenState.NeedsConfirmation(title, result.candidates)
            }
            is SourceResolutionResult.NotFound -> {
                _state.value = SourceResolverScreenState.NotFound(
                    title = title,
                    language = language,
                    searchedSourceIds = result.searchedSourceIds,
                    canBroaden = result.canBroaden,
                )
            }
            is SourceResolutionResult.NoPreferredSources -> {
                _state.value = SourceResolverScreenState.NoPreferredSources(title, result.language)
            }
            is SourceResolutionResult.Conflict -> {
                _state.value = SourceResolverScreenState.Conflict(title, result.existingCanonicalTitleId)
            }
        }
    }
}
