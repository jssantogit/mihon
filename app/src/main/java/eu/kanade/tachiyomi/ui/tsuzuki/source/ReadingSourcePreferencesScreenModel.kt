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
import tachiyomi.domain.tsuzuki.source.interactor.GetPreferredReadingSources
import tachiyomi.domain.tsuzuki.source.interactor.SetPreferredReadingSources
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceDescriptor
import tachiyomi.domain.tsuzuki.source.model.ReadingSourcePreference
import tachiyomi.domain.tsuzuki.source.service.ReadingSourceGateway

@Immutable
sealed interface ReadingSourcePreferencesScreenState {
    data object Loading : ReadingSourcePreferencesScreenState

    data class Success(
        val languages: List<String>,
        val selectedLanguage: String,
        val installedSources: List<ReadingSourceDescriptor>,
        val configuredSources: List<ReadingSourceDescriptor>,
        val availableSources: List<ReadingSourceDescriptor>,
        val savedSourceIds: List<Long>,
        val pendingSourceIds: List<Long>,
        val isSaving: Boolean = false,
    ) : ReadingSourcePreferencesScreenState {
        val hasPendingChanges: Boolean
            get() = savedSourceIds != pendingSourceIds
    }

    data class Error(
        val error: Throwable,
        val selectedLanguage: String? = null,
    ) : ReadingSourcePreferencesScreenState
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ReadingSourcePreferencesScreenModel(
    private val getPreferredReadingSources: GetPreferredReadingSources,
    private val setPreferredReadingSources: SetPreferredReadingSources,
    private val readingSourceGateway: ReadingSourceGateway,
) : ViewModel() {

    private val _state =
        MutableStateFlow<ReadingSourcePreferencesScreenState>(ReadingSourcePreferencesScreenState.Loading)
    val state: StateFlow<ReadingSourcePreferencesScreenState> = _state.asStateFlow()

    private val pendingByLanguage = mutableMapOf<String, List<Long>>()
    private val savedByLanguage = mutableMapOf<String, List<Long>>()
    private val installedByLanguage = mutableMapOf<String, List<ReadingSourceDescriptor>>()
    private var languages: List<String> = emptyList()
    private var selectedLanguage: String? = null
    private var loadJob: Job? = null

    init {
        loadInitial()
    }

    fun selectLanguage(language: String): Job? {
        val normalized = language.trim()
        if (normalized.isEmpty()) return null
        if (selectedLanguage == normalized && _state.value is ReadingSourcePreferencesScreenState.Success) return null

        rememberPendingEdits()
        selectedLanguage = normalized
        languages = (languages + normalized).distinct().sorted()
        return loadLanguage(normalized)
    }

    fun addSource(sourceId: Long) {
        updatePending { pending ->
            if (pending.contains(sourceId) || installedSources().none { it.sourceId == sourceId }) {
                pending
            } else {
                pending + sourceId
            }
        }
    }

    fun removeSource(sourceId: Long) {
        updatePending { pending -> pending - sourceId }
    }

    fun moveUp(index: Int) {
        updatePending { pending ->
            if (index <= 0 || index >= pending.size) return@updatePending pending
            pending.toMutableList().also {
                val sourceId = it.removeAt(index)
                it.add(index - 1, sourceId)
            }
        }
    }

    fun moveDown(index: Int) {
        updatePending { pending ->
            if (index < 0 || index >= pending.lastIndex) return@updatePending pending
            pending.toMutableList().also {
                val sourceId = it.removeAt(index)
                it.add(index + 1, sourceId)
            }
        }
    }

    fun save(): Job? {
        val current = _state.value as? ReadingSourcePreferencesScreenState.Success ?: return null
        val language = current.selectedLanguage.takeIf { it.isNotBlank() } ?: return null
        val pending = current.pendingSourceIds.toList()
        _state.value = current.copy(isSaving = true)
        return viewModelScope.launch {
            try {
                setPreferredReadingSources.execute(language, pending)
                savedByLanguage[language] = pending
                pendingByLanguage[language] = pending
                _state.value = current.copy(
                    savedSourceIds = pending,
                    pendingSourceIds = pending,
                    isSaving = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = current.copy(isSaving = false)
                _state.value = ReadingSourcePreferencesScreenState.Error(e, language)
            }
        }
    }

    private fun loadInitial() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                languages = getPreferredReadingSources.getConfiguredLanguages().distinct().sorted()
                val initial = languages.firstOrNull()
                if (initial == null) {
                    _state.value = ReadingSourcePreferencesScreenState.Success(
                        languages = emptyList(),
                        selectedLanguage = "",
                        installedSources = emptyList(),
                        configuredSources = emptyList(),
                        availableSources = emptyList(),
                        savedSourceIds = emptyList(),
                        pendingSourceIds = emptyList(),
                    )
                } else {
                    selectedLanguage = initial
                    loadLanguageInternal(initial)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = ReadingSourcePreferencesScreenState.Error(e)
            }
        }
    }

    private fun loadLanguage(language: String): Job {
        loadJob?.cancel()
        _state.value = ReadingSourcePreferencesScreenState.Loading
        loadJob = viewModelScope.launch {
            try {
                loadLanguageInternal(language)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = ReadingSourcePreferencesScreenState.Error(e, language)
            }
        }
        return loadJob!!
    }

    private suspend fun loadLanguageInternal(language: String) {
        val saved = savedByLanguage[language]
            ?: getPreferredReadingSources.await(language).map(ReadingSourcePreference::sourceId).also {
                savedByLanguage[language] = it
            }
        val installed = readingSourceGateway.listInstalled(language)
            .filter { it.language.equals(language, ignoreCase = true) }
            .distinctBy { it.sourceId }
            .sortedBy { it.name.lowercase() }
        installedByLanguage[language] = installed

        val pending = pendingByLanguage[language] ?: saved
        pendingByLanguage[language] = pending
        selectedLanguage = language
        _state.value = buildSuccess(language, installed, saved, pending)
    }

    private fun buildSuccess(
        language: String,
        installed: List<ReadingSourceDescriptor>,
        saved: List<Long>,
        pending: List<Long>,
        isSaving: Boolean = false,
    ): ReadingSourcePreferencesScreenState.Success {
        val byId = installed.associateBy { it.sourceId }
        fun descriptor(sourceId: Long) = byId[sourceId]
            ?: ReadingSourceDescriptor(sourceId, "Source $sourceId", language)

        val configured = pending.map(::descriptor)
        val configuredIds = pending.toSet()
        return ReadingSourcePreferencesScreenState.Success(
            languages = languages,
            selectedLanguage = language,
            installedSources = installed,
            configuredSources = configured,
            availableSources = installed.filterNot { it.sourceId in configuredIds },
            savedSourceIds = saved,
            pendingSourceIds = pending,
            isSaving = isSaving,
        )
    }

    private fun updatePending(transform: (List<Long>) -> List<Long>) {
        val current = _state.value as? ReadingSourcePreferencesScreenState.Success ?: return
        val next = transform(current.pendingSourceIds)
        pendingByLanguage[current.selectedLanguage] = next
        _state.value = current.copy(
            configuredSources = next.map { sourceId ->
                current.installedSources.firstOrNull { it.sourceId == sourceId }
                    ?: ReadingSourceDescriptor(sourceId, "Source $sourceId", current.selectedLanguage)
            },
            availableSources = current.installedSources.filterNot { it.sourceId in next },
            pendingSourceIds = next,
        )
    }

    private fun installedSources(): List<ReadingSourceDescriptor> {
        val current = _state.value as? ReadingSourcePreferencesScreenState.Success ?: return emptyList()
        return current.installedSources
    }

    private fun rememberPendingEdits() {
        val current = _state.value as? ReadingSourcePreferencesScreenState.Success ?: return
        if (current.selectedLanguage.isNotBlank()) {
            pendingByLanguage[current.selectedLanguage] = current.pendingSourceIds
        }
    }
}
