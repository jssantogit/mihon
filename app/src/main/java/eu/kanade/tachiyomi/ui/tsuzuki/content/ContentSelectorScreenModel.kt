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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
import tachiyomi.domain.tsuzuki.chapter.evidence.RefreshChapterEvidence
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentOption
import tachiyomi.domain.tsuzuki.content.ContentPreference
import tachiyomi.domain.tsuzuki.content.interactor.DiscoverReadableChapter
import tachiyomi.domain.tsuzuki.content.interactor.FastDiscoveryCompletion
import tachiyomi.domain.tsuzuki.content.interactor.FastReadingDiscoveryEvent
import tachiyomi.domain.tsuzuki.content.interactor.ResolveChapterContent
import tachiyomi.domain.tsuzuki.content.repository.ContentPreferenceRepository
import java.util.concurrent.atomic.AtomicInteger
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
        val failedProviderCount: Int = 0,
    ) : ContentSelectorScreenState

    data class Discovering(
        val canonicalTitleId: String,
        val canonicalChapterId: String,
        val addonCount: Int,
        val failedAttempts: Int = 0,
        val confirmationRequired: Boolean = false,
    ) : ContentSelectorScreenState

    data class Empty(
        val canonicalTitleId: String,
        val canonicalChapterId: String,
        val noEnabledAddon: Boolean = false,
        val discoveryAttempted: Boolean = false,
        val confirmationRequired: Boolean = false,
        val timedOut: Boolean = false,
        val failedAttempts: Int = 0,
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
    private val refreshChapterEvidence: RefreshChapterEvidence? = null,
    private val discoverReadableChapter: DiscoverReadableChapter? = null,
) : ViewModel() {

    @Inject
    constructor(
        resolveChapterContent: ResolveChapterContent,
        contentPreferenceRepository: ContentPreferenceRepository,
        addonRepository: AddonRepository,
        refreshChapterEvidence: RefreshChapterEvidence,
        discoverReadableChapter: DiscoverReadableChapter,
    ) : this(
        resolveChapterContent = resolveChapterContent,
        contentPreferenceRepository = contentPreferenceRepository,
        addonRepository = addonRepository,
        clock = { Clock.System.now().toEpochMilliseconds() },
        refreshChapterEvidence = refreshChapterEvidence,
        discoverReadableChapter = discoverReadableChapter,
    )

    private val _state = MutableStateFlow<ContentSelectorScreenState>(ContentSelectorScreenState.Loading)
    val state: StateFlow<ContentSelectorScreenState> = _state.asStateFlow()

    private var canonicalTitleId: String? = null
    private var canonicalChapterId: String? = null
    private var loadJob: Job? = null
    private var pendingBindingRefreshJob: Job? = null
    private var pendingBindingTitleId: String? = null
    private val preferenceWriteMutex = Mutex()

    fun start(
        canonicalTitleId: String,
        canonicalChapterId: String,
    ): Job {
        val sameRequest = this.canonicalTitleId == canonicalTitleId &&
            this.canonicalChapterId == canonicalChapterId
        if (sameRequest && loadJob?.isActive == true) return loadJob!!

        this.canonicalTitleId = canonicalTitleId
        this.canonicalChapterId = canonicalChapterId
        // Re-entering a selector while a newly linked source is still being reconciled
        // must not cache an early EMPTY result.
        val pending = pendingBindingRefreshJob?.takeIf {
            pendingBindingTitleId == canonicalTitleId && it.isActive
        }
        if (pending != null) {
            loadJob?.cancel()
            _state.value = ContentSelectorScreenState.Loading
            return pending
        }
        return load(refresh = false)
    }

    /**
     * Stop only the user-visible automatic session. The existing Reader Activity
     * and its selected chapter remain untouched when a sheet is dismissed.
     */
    fun cancelDiscovery() {
        val current = _state.value
        if (current is ContentSelectorScreenState.Discovering) {
            loadJob?.cancel()
            _state.value = ContentSelectorScreenState.Empty(
                canonicalTitleId = current.canonicalTitleId,
                canonicalChapterId = current.canonicalChapterId,
                discoveryAttempted = true,
                confirmationRequired = current.confirmationRequired,
                failedAttempts = current.failedAttempts,
            )
        } else if (current is ContentSelectorScreenState.Ready || current is ContentSelectorScreenState.Loading) {
            loadJob?.cancel()
        }
    }

    fun retry(): Job? {
        if (canonicalTitleId == null || canonicalChapterId == null) return null
        val pending = pendingBindingRefreshJob?.takeIf {
            pendingBindingTitleId == canonicalTitleId && it.isActive
        }
        return pending ?: load(refresh = true)
    }

    /**
     * One post-link path for the detail and inline Reader: persist binding first,
     * refresh and reconcile observed chapter evidence, invalidate cached options
     * through the refresh interactor, then query the CURRENT selected chapter.
     * A title without an open selector still refreshes its chapter evidence.
     */
    suspend fun refreshAfterBinding(changedTitleId: String): Result<Unit> {
        val refresher = checkNotNull(refreshChapterEvidence) {
            "Chapter evidence refresh is required for post-binding selection"
        }
        val caller = currentCoroutineContext()[Job]
        pendingBindingRefreshJob = caller
        pendingBindingTitleId = changedTitleId
        if (canonicalTitleId == changedTitleId) {
            loadJob?.cancel()
            _state.value = ContentSelectorScreenState.Loading
        }
        try {
            val refreshed = try {
                refresher.execute(changedTitleId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
            if (caller?.isActive != false && canonicalTitleId == changedTitleId) {
                val chapterId = canonicalChapterId
                if (chapterId != null) {
                    if (refreshed.isSuccess) {
                        load(refresh = true, allowDiscovery = false).join()
                    } else {
                        _state.value = ContentSelectorScreenState.Error(
                            canonicalTitleId = changedTitleId,
                            canonicalChapterId = chapterId,
                            error = requireNotNull(refreshed.exceptionOrNull()),
                        )
                    }
                }
            }
            return refreshed
        } finally {
            if (pendingBindingRefreshJob === caller) {
                pendingBindingRefreshJob = null
                pendingBindingTitleId = null
            }
        }
    }

    /**
     * Follow the exact persisted editions returned by explicit Link Add-on.
     * Do not refresh unrelated MangaDex languages or re-query every provider
     * while waiting to present the selected chapter.
     */
    suspend fun refreshAfterBindings(request: BindingRefreshRequest): Result<Unit> {
        val refresher = checkNotNull(refreshChapterEvidence) {
            "Chapter evidence refresh is required for post-binding selection"
        }
        val titleId = request.canonicalTitleId
        val caller = currentCoroutineContext()[Job]
        pendingBindingRefreshJob = caller
        pendingBindingTitleId = titleId
        if (canonicalTitleId == titleId) {
            loadJob?.cancel()
            _state.value = ContentSelectorScreenState.Loading
        }
        val successful = AtomicInteger()
        val failed = AtomicInteger()
        val firstFailure = mutableListOf<Throwable>()
        val failureGate = Mutex()
        val publicationGate = Mutex()
        return try {
            coroutineScope {
                val gate = Semaphore(MAX_CONCURRENT_MANUAL_BINDING_REFRESHES)
                request.bindings.distinctBy(ContentBinding::id).map { binding ->
                    async {
                        gate.withPermit {
                            val refreshed = try {
                                refresher.executeForBinding(binding)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                Result.failure(error)
                            }
                            if (refreshed.isFailure) {
                                failed.incrementAndGet()
                                failureGate.withLock {
                                    firstFailure.add(
                                        requireNotNull(refreshed.exceptionOrNull()),
                                    )
                                }
                                return@withPermit
                            }
                            successful.incrementAndGet()
                            if (canonicalTitleId != titleId) return@withPermit
                            val chapterId = canonicalChapterId ?: return@withPermit
                            val lookup = try {
                                resolveChapterContent.lookupBindingOptions(binding, chapterId)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                failed.incrementAndGet()
                                failureGate.withLock { firstFailure.add(error) }
                                return@withPermit
                            }
                            if (lookup.failedProviders.isNotEmpty()) {
                                failed.addAndGet(lookup.failedProviders.size)
                            }
                            if (lookup.options.isNotEmpty()) {
                                publicationGate.withLock {
                                    currentCoroutineContext().ensureActive()
                                    if (canonicalTitleId == titleId && canonicalChapterId == chapterId) {
                                        publishVerifiedOptions(
                                            titleId = titleId,
                                            chapterId = chapterId,
                                            incoming = lookup.options,
                                            failures = failed.get(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
            if (canonicalTitleId == titleId && _state.value is ContentSelectorScreenState.Loading) {
                canonicalChapterId?.let { chapterId ->
                    _state.value = ContentSelectorScreenState.Empty(
                        canonicalTitleId = titleId,
                        canonicalChapterId = chapterId,
                        failedAttempts = failed.get(),
                        noEnabledAddon = addonRepository.snapshot().none { it.enabled },
                    )
                }
            }
            if (successful.get() > 0) {
                Result.success(Unit)
            } else {
                Result.failure(
                    firstFailure.firstOrNull()
                        ?: IllegalStateException("No newly linked reading edition could be refreshed"),
                )
            }
        } finally {
            if (pendingBindingRefreshJob === caller) {
                pendingBindingRefreshJob = null
                pendingBindingTitleId = null
            }
        }
    }

    private suspend fun publishVerifiedOptions(
        titleId: String,
        chapterId: String,
        incoming: List<ContentOption>,
        failures: Int,
    ) {
        if (incoming.isEmpty()) return
        val previous = (_state.value as? ContentSelectorScreenState.Ready)
            ?.takeIf { it.canonicalTitleId == titleId && it.canonicalChapterId == chapterId }
            ?.options
            .orEmpty()
            .map(ContentOptionPresentation::option)
        val options = (previous + incoming).distinctBy(ContentOption::key)
        val preference = contentPreferenceRepository.get(titleId)
        val names = addonRepository.snapshot().associate { it.id to it.displayName }
        val preferred = preference?.preferredAddonId
        _state.value = ContentSelectorScreenState.Ready(
            canonicalTitleId = titleId,
            canonicalChapterId = chapterId,
            options = options.map { option ->
                ContentOptionPresentation(
                    option = option,
                    addonDisplayName = names[option.addonId] ?: option.addonId.value,
                    language = option.language,
                    scanlationGroup = option.scanlationGroup,
                    releaseDate = option.releaseDate,
                )
            },
            preferredAddonId = preferred,
            preferredOptionKey = options.firstOrNull { it.addonId == preferred }?.key,
            preferredLanguage = preference?.preferredLanguage,
            preferredUnavailable = preferred != null && options.none { it.addonId == preferred },
            failedProviderCount = failures,
        )
    }

    // First selection becomes the per-title preference; replacing an existing preference requires confirmation.
    fun select(item: ContentOptionPresentation): SelectionResult {
        val state = _state.value as? ContentSelectorScreenState.Ready
            ?: error("Content options are not ready")
        require(state.options.any { it.option.key == item.option.key }) {
            "Selected option is not part of the current selector"
        }
        // Do not keep searching in the background once the reader has selected
        // its verified content option.
        cancelDiscovery()
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
            preferenceWriteMutex.withLock {
                val existing = contentPreferenceRepository.get(selection.canonicalTitleId)
                if (existing?.preferredAddonId == null) {
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
        }
    }

    fun confirmPreferred(selection: SelectionResult): Job {
        return viewModelScope.launch {
            preferenceWriteMutex.withLock {
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
    }

    fun confirmPreferredLanguage(selection: SelectionResult): Job {
        val language = requireNotNull(selection.option.language).trim()
        require(language.isNotEmpty()) { "Preferred language cannot be blank" }
        return viewModelScope.launch {
            preferenceWriteMutex.withLock {
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
    }

    /**
     * One cold session per selected chapter. The first verified option is
     * published immediately; later failures cannot replace a ready selector.
     * Explicit manual links keep their existing refresh path.
     */
    private suspend fun runAutomaticDiscovery(titleId: String, chapterId: String) {
        val discoverer = requireNotNull(discoverReadableChapter)
        var failures = 0
        var needsConfirmation = false
        var addonCount = 0
        discoverer.discover(titleId, chapterId).collect { event ->
            currentCoroutineContext().ensureActive()
            when (event) {
                is FastReadingDiscoveryEvent.Searching -> {
                    addonCount = event.targets.size
                    _state.value = ContentSelectorScreenState.Discovering(
                        canonicalTitleId = titleId,
                        canonicalChapterId = chapterId,
                        addonCount = addonCount,
                        failedAttempts = failures,
                        confirmationRequired = needsConfirmation,
                    )
                }
                is FastReadingDiscoveryEvent.Ready -> {
                    if (event.options.isEmpty()) return@collect
                    publishVerifiedOptions(titleId, chapterId, event.options, failures)
                }
                is FastReadingDiscoveryEvent.ConfirmationRequired -> {
                    needsConfirmation = true
                    val current = _state.value
                    if (current is ContentSelectorScreenState.Discovering) {
                        _state.value = current.copy(confirmationRequired = true)
                    }
                }
                is FastReadingDiscoveryEvent.SourceFailed -> {
                    failures++
                    when (val current = _state.value) {
                        is ContentSelectorScreenState.Discovering -> {
                            _state.value = current.copy(failedAttempts = failures)
                        }
                        is ContentSelectorScreenState.Ready -> {
                            _state.value = current.copy(failedProviderCount = failures)
                        }
                        else -> Unit
                    }
                }
                is FastReadingDiscoveryEvent.Completed -> {
                    if (_state.value is ContentSelectorScreenState.Ready) return@collect
                    _state.value = ContentSelectorScreenState.Empty(
                        canonicalTitleId = titleId,
                        canonicalChapterId = chapterId,
                        noEnabledAddon = addonRepository.snapshot().none { it.enabled },
                        discoveryAttempted = true,
                        confirmationRequired = needsConfirmation ||
                            event.reason == FastDiscoveryCompletion.CONFIRMATION_REQUIRED,
                        timedOut = event.reason == FastDiscoveryCompletion.TIME_BUDGET,
                        failedAttempts = failures,
                    )
                }
            }
        }
    }

    private fun load(refresh: Boolean, allowDiscovery: Boolean = true): Job {
        val titleId = requireNotNull(canonicalTitleId)
        val chapterId = requireNotNull(canonicalChapterId)
        loadJob?.cancel()
        _state.value = ContentSelectorScreenState.Loading
        loadJob = viewModelScope.launch {
            try {
                if (allowDiscovery && discoverReadableChapter != null) {
                    val requestJob = currentCoroutineContext()[Job]
                    // Third-party Java extensions may ignore coroutine cancellation.
                    // A separate Main-scope deadline stops the visible spinner even
                    // when the IO worker has not cooperated with its timeout.
                    val visibleDeadline = viewModelScope.launch {
                        delay(VISIBLE_DISCOVERY_DEADLINE_MILLIS)
                        if (loadJob !== requestJob) return@launch
                        val current = _state.value
                        if (current == ContentSelectorScreenState.Loading ||
                            current is ContentSelectorScreenState.Discovering
                        ) {
                            loadJob?.cancel()
                            _state.value = ContentSelectorScreenState.Empty(
                                canonicalTitleId = titleId,
                                canonicalChapterId = chapterId,
                                discoveryAttempted = true,
                                timedOut = true,
                                failedAttempts = (current as? ContentSelectorScreenState.Discovering)
                                    ?.failedAttempts ?: 0,
                                confirmationRequired = (current as? ContentSelectorScreenState.Discovering)
                                    ?.confirmationRequired ?: false,
                            )
                        }
                    }
                    try {
                        runAutomaticDiscovery(titleId, chapterId)
                    } finally {
                        visibleDeadline.cancel()
                    }
                    return@launch
                }
                val preference = contentPreferenceRepository.get(titleId)
                val lookup = resolveChapterContent.lookupOptions(
                    canonicalTitleId = titleId,
                    canonicalChapterId = chapterId,
                    refresh = refresh,
                )
                val options = lookup.options
                if (options.isEmpty() && lookup.failedProviders.isNotEmpty()) {
                    _state.value = ContentSelectorScreenState.Error(
                        canonicalTitleId = titleId,
                        canonicalChapterId = chapterId,
                        error = IllegalStateException(
                            "Could not query ${lookup.failedProviders.size} reading Add-on(s). " +
                                "Retry or choose another source.",
                        ),
                    )
                    return@launch
                }
                if (options.isEmpty()) {
                    _state.value = ContentSelectorScreenState.Empty(
                        canonicalTitleId = titleId,
                        canonicalChapterId = chapterId,
                        noEnabledAddon = addonRepository.snapshot().none { it.enabled },
                    )
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
                    failedProviderCount = lookup.failedProviders.size,
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
    private companion object {
        const val VISIBLE_DISCOVERY_DEADLINE_MILLIS = 10_000L
        const val MAX_CONCURRENT_MANUAL_BINDING_REFRESHES = 2
    }
}
