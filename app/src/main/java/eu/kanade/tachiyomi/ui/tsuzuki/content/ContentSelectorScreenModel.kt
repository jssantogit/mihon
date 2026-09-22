package eu.kanade.tachiyomi.ui.tsuzuki.content

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
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
import tachiyomi.domain.tsuzuki.content.ContentOption
import tachiyomi.domain.tsuzuki.content.ContentPreference
import tachiyomi.domain.tsuzuki.content.interactor.ResolveChapterContent
import tachiyomi.domain.tsuzuki.content.repository.ContentPreferenceRepository
import kotlin.time.Clock

@Immutable
data class ContentOptionPresentation(
    val option: ContentOption,
    val addonDisplayName: String,
    val language: String?,
    val scanlationGroup: String?,
    val releaseDate: Long?,
)

@Immutable
sealed interface ContentSelectorScreenState {
    data object Loading : ContentSelectorScreenState

    data class Ready(
        val canonicalTitleId: String,
        val canonicalChapterId: String,
        val options: List<ContentOptionPresentation>,
        val preferredAddonId: AddonId?,
        val preferredOptionKey: String?,
        val preferredLanguage: String?,
        val preferredUnavailable: Boolean,
    ) : ContentSelectorScreenState

    data class Empty(
        val canonicalTitleId: String,
        val canonicalChapterId: String,
    ) : ContentSelectorScreenState

    data class Error(
        val canonicalTitleId: String,
        val canonicalChapterId: String,
        val error: Throwable,
    ) : ContentSelectorScreenState
}

data class SelectionResult(
    val canonicalTitleId: String,
    val option: ContentOption,
    val offerSetAsPreferred: Boolean,
    val rememberFirstPreference: Boolean = false,
    val offerSetLanguagePreferred: Boolean = false,
    val addonDisplayName: String? = null,
)

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ContentSelectorScreenModel internal constructor(
    private val resolveChapterContent: ResolveChapterContent,
    private val contentPreferenceRepository: ContentPreferenceRepository,
    private val addonRepository: AddonRepository,
    private val clock: () -> Long,
) : ViewModel() {

    @Inject
    constructor(
        resolveChapterContent: ResolveChapterContent,
        contentPreferenceRepository: ContentPreferenceRepository,
        addonRepository: AddonRepository,
    ) : this(
        resolveChapterContent = resolveChapterContent,
        contentPreferenceRepository = contentPreferenceRepository,
        addonRepository = addonRepository,
        clock = { Clock.System.now().toEpochMilliseconds() },
    )

    private val _state = MutableStateFlow<ContentSelectorScreenState>(ContentSelectorScreenState.Loading)
    val state: StateFlow<ContentSelectorScreenState> = _state.asStateFlow()

    private var canonicalTitleId: String? = null
    private var canonicalChapterId: String? = null
    private var loadJob: Job? = null

    fun start(
        canonicalTitleId: String,
        canonicalChapterId: String,
    ): Job {
        val sameRequest = this.canonicalTitleId == canonicalTitleId &&
            this.canonicalChapterId == canonicalChapterId
        if (sameRequest && loadJob?.isActive == true) return loadJob!!

        this.canonicalTitleId = canonicalTitleId
        this.canonicalChapterId = canonicalChapterId
        return load(refresh = false)
    }

    fun retry(): Job? {
        if (canonicalTitleId == null || canonicalChapterId == null) return null
        return load(refresh = true)
    }

    // First selection becomes the per-title preference; replacing an existing preference requires confirmation.
    fun select(item: ContentOptionPresentation): SelectionResult {
        val state = _state.value as? ContentSelectorScreenState.Ready
            ?: error("Content options are not ready")
        require(state.options.any { it.option.key == item.option.key }) {
            "Selected option is not part of the current selector"
        }
        val hasExistingPreference = state.preferredAddonId != null
        // Selection is provisional until the Reader has successfully prepared
        // nonempty pages. Never persist an unavailable source as preferred.
        return SelectionResult(
            canonicalTitleId = state.canonicalTitleId,
            option = item.option,
            offerSetAsPreferred = hasExistingPreference &&
                state.preferredAddonId != item.option.addonId,
            rememberFirstPreference = !hasExistingPreference,
            offerSetLanguagePreferred = item.language != null &&
                !item.language.equals(state.preferredLanguage, ignoreCase = true),
            addonDisplayName = item.addonDisplayName,
        )
    }

    fun confirmInitialPreferred(selection: SelectionResult): Job {
        require(selection.rememberFirstPreference) { "Only an initial selection can set this preference" }
        return viewModelScope.launch {
            if (contentPreferenceRepository.get(selection.canonicalTitleId)?.preferredAddonId == null) {
                contentPreferenceRepository.upsert(
                    ContentPreference(
                        canonicalTitleId = selection.canonicalTitleId,
                        preferredAddonId = selection.option.addonId,
                        preferredLanguage = contentPreferenceRepository
                            .get(selection.canonicalTitleId)?.preferredLanguage,
                        updatedAt = clock(),
                    ),
                )
            }
        }
    }

    fun confirmPreferred(selection: SelectionResult): Job {
        return viewModelScope.launch {
            val existing = contentPreferenceRepository.get(selection.canonicalTitleId)
            contentPreferenceRepository.upsert(
                ContentPreference(
                    canonicalTitleId = selection.canonicalTitleId,
                    preferredAddonId = selection.option.addonId,
                    preferredLanguage = existing?.preferredLanguage,
                    updatedAt = clock(),
                ),
            )
        }
    }

    fun confirmPreferredLanguage(selection: SelectionResult): Job {
        val language = requireNotNull(selection.option.language).trim()
        require(language.isNotEmpty()) { "Preferred language cannot be blank" }
        return viewModelScope.launch {
            val existing = contentPreferenceRepository.get(selection.canonicalTitleId)
            contentPreferenceRepository.upsert(
                ContentPreference(
                    canonicalTitleId = selection.canonicalTitleId,
                    preferredAddonId = existing?.preferredAddonId,
                    preferredLanguage = language,
                    updatedAt = clock(),
                ),
            )
        }
    }

    private fun load(refresh: Boolean): Job {
        val titleId = requireNotNull(canonicalTitleId)
        val chapterId = requireNotNull(canonicalChapterId)
        loadJob?.cancel()
        _state.value = ContentSelectorScreenState.Loading
        loadJob = viewModelScope.launch {
            try {
                val preference = contentPreferenceRepository.get(titleId)
                val options = resolveChapterContent.resolveOptions(
                    canonicalTitleId = titleId,
                    canonicalChapterId = chapterId,
                    refresh = refresh,
                )
                if (options.isEmpty()) {
                    _state.value = ContentSelectorScreenState.Empty(titleId, chapterId)
                    return@launch
                }

                val addonNames = addonRepository.snapshot().associate { it.id to it.displayName }
                val presented = options.map { option ->
                    ContentOptionPresentation(
                        option = option,
                        addonDisplayName = addonNames[option.addonId] ?: option.addonId.value,
                        language = option.language,
                        scanlationGroup = option.scanlationGroup,
                        releaseDate = option.releaseDate,
                    )
                }
                val preferredAddonId = preference?.preferredAddonId
                _state.value = ContentSelectorScreenState.Ready(
                    canonicalTitleId = titleId,
                    canonicalChapterId = chapterId,
                    options = presented,
                    preferredAddonId = preferredAddonId,
                    // Preference remains Add-on scoped. Only the highest-ranked
                    // option inside that Add-on is the effective automatic choice.
                    preferredOptionKey = preferredAddonId?.let { preferred ->
                        options.firstOrNull { it.addonId == preferred }?.key
                    },
                    preferredLanguage = preference?.preferredLanguage,
                    preferredUnavailable = preferredAddonId != null &&
                        options.none { it.addonId == preferredAddonId },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.value = ContentSelectorScreenState.Error(
                    canonicalTitleId = titleId,
                    canonicalChapterId = chapterId,
                    error = error,
                )
            }
        }
        return loadJob!!
    }
}
