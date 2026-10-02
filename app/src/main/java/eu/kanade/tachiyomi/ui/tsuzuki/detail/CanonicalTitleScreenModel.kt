package eu.kanade.tachiyomi.ui.tsuzuki.detail

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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
import tachiyomi.domain.tsuzuki.artwork.ResolveCanonicalArtwork
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticEvent
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticLabels
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticOutcome
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticReason
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticStage
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.chapter.diagnostics.NoOpChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.chapter.diagnostics.recordIfEnabled
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.evidence.RefreshChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.interactor.MaterializeInferredChapter
import tachiyomi.domain.tsuzuki.chapter.interactor.buildCanonicalChapterOutline
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.content.ContentOption
import tachiyomi.domain.tsuzuki.download.interactor.DownloadCanonicalChapter
import tachiyomi.domain.tsuzuki.download.interactor.GetCanonicalChapterDownloadState
import tachiyomi.domain.tsuzuki.download.model.CanonicalDownloadPreparation
import tachiyomi.domain.tsuzuki.download.repository.CanonicalDownloadRepository
import tachiyomi.domain.tsuzuki.integration.interactor.ResolveCanonicalMetadata
import tachiyomi.domain.tsuzuki.integration.model.ResolvedMetadata
import tachiyomi.domain.tsuzuki.integration.model.TsuzukiRating
import tachiyomi.domain.tsuzuki.metadata.ReportedChapterCount
import tachiyomi.domain.tsuzuki.metadata.interactor.RefreshReportedChapterCounts
import tachiyomi.domain.tsuzuki.metadata.repository.ReportedChapterCountRepository
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import tachiyomi.domain.tsuzuki.source.interactor.ResolveCanonicalSourceManga
import kotlin.time.Clock
import kotlin.time.TimeSource

@Immutable
sealed interface CanonicalTitleScreenState {
    data object Loading : CanonicalTitleScreenState

    data class Loaded(
        val title: CanonicalTitle,
        val libraryEntry: CanonicalLibraryEntry?,
        val chapters: List<CanonicalChapterDetailItem>,
        val reportedChapterCounts: List<ReportedChapterCount> = emptyList(),
        val addonCoverage: List<ObservedAddonCoverage> = emptyList(),
        val coverUrl: String? = null,
        val sourceCover: MangaCover? = null,
        val author: String? = null,
        val description: String? = null,
        val genres: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val editorialStatus: String? = null,
        val editorialFormat: String? = null,
        val ratingValue: Double? = null,
        val ratingMaxValue: Double? = null,
        val ratingVoteCount: Int? = null,
        val ratings: List<CanonicalProviderRating> = emptyList(),
        val tsuzukiRating: TsuzukiRating? = null,
        val startDate: String? = null,
        val endDate: String? = null,
        val editorialVolumeCount: Int? = null,
        val metadataSources: List<String> = emptyList(),
        val isRefreshing: Boolean = false,
        val refreshError: Throwable? = null,
        val libraryMutationInProgress: Boolean = false,
        val libraryMutationError: Throwable? = null,
        val downloadInProgressChapterId: String? = null,
        val downloadSelectionChapterId: String? = null,
        val downloadError: Throwable? = null,
        val chapterActionError: Throwable? = null,
        val chapterDiagnosticsRecording: Boolean = false,
        val chapterDiagnosticReportAvailable: Boolean = false,
    ) : CanonicalTitleScreenState

    data class Error(
        val error: Throwable,
    ) : CanonicalTitleScreenState
}

@Immutable
data class CanonicalProviderRating(
    val providerId: String,
    val value: Double,
    val maxValue: Double,
    val voteCount: Int? = null,
)

@Immutable
data class CanonicalChapterDetailItem(
    val chapter: CanonicalChapter,
    val progress: CanonicalChapterProgress?,
    val downloaded: Boolean,
    val inferredFromCount: Boolean = false,
) {
    val confirmation: CanonicalChapterConfirmation
        get() = chapter.confirmation
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class CanonicalTitleScreenModel(
    private val canonicalTitleRepository: CanonicalTitleRepository,
    private val canonicalLibraryRepository: CanonicalLibraryRepository,
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val materializeInferredChapter: MaterializeInferredChapter,
    private val chapterEvidenceRepository: ChapterEvidenceRepository,
    private val canonicalReadingRepository: CanonicalReadingRepository,
    private val getCanonicalChapterDownloadState: GetCanonicalChapterDownloadState,
    private val downloadCanonicalChapter: DownloadCanonicalChapter,
    private val canonicalDownloadRepository: CanonicalDownloadRepository,
    private val reportedChapterCountRepository: ReportedChapterCountRepository,
    private val addonRepository: AddonRepository,
    private val refreshReportedChapterCounts: RefreshReportedChapterCounts,
    private val refreshChapterEvidence: RefreshChapterEvidence,
    private val sourceTitleMappingRepository: SourceTitleMappingRepository? = null,
    private val mangaRepository: MangaRepository? = null,
    private val resolveCanonicalMetadata: ResolveCanonicalMetadata,
    private val resolveCanonicalSourceManga: ResolveCanonicalSourceManga,
    private val resolveCanonicalArtwork: ResolveCanonicalArtwork,
    private val diagnostics: ChapterInventoryDiagnostics = NoOpChapterInventoryDiagnostics,
) : ViewModel() {

    private val _state = MutableStateFlow<CanonicalTitleScreenState>(CanonicalTitleScreenState.Loading)
    val state: StateFlow<CanonicalTitleScreenState> = _state.asStateFlow()

    private var canonicalTitleId: String? = null
    private var diagnosticTitleId: String? = null
    private var operation: Job? = null
    private var downloadOperation: Job? = null

    fun start(canonicalTitleId: String): Job {
        val previousTitleId = this.canonicalTitleId
        if (previousTitleId != null && previousTitleId != canonicalTitleId && diagnosticsRecording(previousTitleId)) {
            runCatching { diagnostics.stop() }
        }
        this.canonicalTitleId = canonicalTitleId
        operation?.cancel()
        operation = viewModelScope.launch {
            val alreadyLoaded = (_state.value as? CanonicalTitleScreenState.Loaded)
                ?.takeIf { it.title.id == canonicalTitleId }
            if (alreadyLoaded == null) {
                loadCachedFirst(canonicalTitleId)
            } else {
                refreshInBackground(canonicalTitleId, forceChapterRefresh = false)
            }
        }
        return operation!!
    }

    fun refresh(): Job? {
        val id = canonicalTitleId ?: return null
        operation?.cancel()
        operation = viewModelScope.launch {
            refreshInBackground(id, forceChapterRefresh = true)
        }
        return operation
    }

    /**
     * The binding flow has already fetched and reconciled chapter evidence.
     * Reload only persisted state here; a second provider refresh can race with
     * the just-completed evidence graph and delay the newly linked chapter list.
     */
    fun reloadReconciledChapters(): Job? {
        val id = canonicalTitleId ?: return null
        operation?.cancel()
        operation = viewModelScope.launch {
            try {
                val refreshed = loadLocalState(
                    canonicalTitleId = id,
                    includeIntegrationMetadata = false,
                    allowSourceNetwork = false,
                    isRefreshing = false,
                )
                if (canonicalTitleId != id) return@launch
                val current = (_state.value as? CanonicalTitleScreenState.Loaded)
                    ?.takeIf { it.title.id == id }
                _state.value = if (current == null) {
                    refreshed
                } else {
                    refreshed.copy(
                        libraryEntry = current.libraryEntry,
                        libraryMutationInProgress = current.libraryMutationInProgress,
                        libraryMutationError = current.libraryMutationError,
                        downloadInProgressChapterId = current.downloadInProgressChapterId,
                        downloadSelectionChapterId = current.downloadSelectionChapterId,
                        downloadError = current.downloadError,
                        chapterActionError = current.chapterActionError,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val current = _state.value as? CanonicalTitleScreenState.Loaded
                _state.value = current?.copy(isRefreshing = false, refreshError = error)
                    ?: CanonicalTitleScreenState.Error(error)
            }
        }
        return operation
    }

    fun startChapterDiagnostics() {
        val id = canonicalTitleId ?: return
        try {
            diagnostics.start(id)
        } catch (_: Exception) {
            return
        }
        diagnosticTitleId = id
        val loaded = _state.value as? CanonicalTitleScreenState.Loaded
        if (loaded?.title?.id == id) {
            recordUiDiagnosticSnapshot(
                state = loaded,
                reason = ChapterInventoryDiagnosticReason.CACHE_SNAPSHOT,
                received = loaded.chapters.count { !it.inferredFromCount },
            )
            updateDiagnosticUiState(id)
        }
    }

    fun stopChapterDiagnostics() {
        try {
            diagnostics.stop()
        } catch (_: Exception) {
            return
        }
        canonicalTitleId?.let(::updateDiagnosticUiState)
    }

    fun clearChapterDiagnostics() {
        try {
            diagnostics.clear()
        } catch (_: Exception) {
            return
        }
        diagnosticTitleId = null
        canonicalTitleId?.let(::updateDiagnosticUiState)
    }

    fun chapterDiagnosticReport(): String = try {
        diagnostics.report()
    } catch (_: Exception) {
        ""
    }

    fun addToLibrary(): Job? {
        val id = canonicalTitleId ?: return null
        val loaded = _state.value as? CanonicalTitleScreenState.Loaded ?: return null
        if (loaded.libraryEntry != null) return null

        _state.value = loaded.copy(
            libraryMutationInProgress = true,
            libraryMutationError = null,
        )
        return viewModelScope.launch {
            try {
                val now = Clock.System.now().toEpochMilliseconds()
                val entry = CanonicalLibraryEntry(
                    canonicalTitleId = id,
                    status = LibraryStatus.PLANNING,
                    favorite = true,
                    addedAt = now,
                    updatedAt = now,
                )
                canonicalLibraryRepository.upsert(entry)
                updateLibraryState(entry)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                updateLibraryError(error)
            }
        }
    }

    fun removeFromLibrary(): Job? {
        val id = canonicalTitleId ?: return null
        val loaded = _state.value as? CanonicalTitleScreenState.Loaded ?: return null
        if (loaded.libraryEntry == null) return null

        _state.value = loaded.copy(
            libraryMutationInProgress = true,
            libraryMutationError = null,
        )
        return viewModelScope.launch {
            try {
                canonicalLibraryRepository.remove(id)
                updateLibraryState(null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                updateLibraryError(error)
            }
        }
    }

    fun openChapter(canonicalChapterId: String, onReady: (String) -> Unit): Job? {
        val loaded = _state.value as? CanonicalTitleScreenState.Loaded ?: return null
        val row = loaded.chapters.firstOrNull { it.chapter.id == canonicalChapterId } ?: return null
        return viewModelScope.launch {
            try {
                val resolved = materializeIfRequired(row)
                val state = _state.value as? CanonicalTitleScreenState.Loaded
                if (state?.title?.id == resolved.canonicalTitleId) {
                    _state.value = state.copy(chapterActionError = null)
                }
                onReady(resolved.id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val state = _state.value as? CanonicalTitleScreenState.Loaded
                if (state != null) _state.value = state.copy(chapterActionError = error)
            }
        }
    }

    private suspend fun materializeIfRequired(row: CanonicalChapterDetailItem): CanonicalChapter {
        if (!row.inferredFromCount) return row.chapter
        val resolved = materializeInferredChapter.execute(row.chapter)
        if (resolved.id != row.chapter.id) {
            val state = _state.value as? CanonicalTitleScreenState.Loaded
            if (state != null) {
                _state.value = state.copy(
                    chapters = state.chapters.map {
                        if (it.chapter.id == row.chapter.id) {
                            it.copy(chapter = resolved, inferredFromCount = false)
                        } else {
                            it
                        }
                    },
                    downloadInProgressChapterId = state.downloadInProgressChapterId
                        ?.let { if (it == row.chapter.id) resolved.id else it },
                )
            }
        }
        return resolved
    }

    fun requestDownload(canonicalChapterId: String): Job? {
        val loaded = _state.value as? CanonicalTitleScreenState.Loaded ?: return null
        if (loaded.chapters.none { it.chapter.id == canonicalChapterId }) return null
        downloadOperation?.cancel()
        _state.value = loaded.copy(
            downloadInProgressChapterId = canonicalChapterId,
            downloadSelectionChapterId = null,
            downloadError = null,
        )
        downloadOperation = viewModelScope.launch {
            try {
                val resolved = materializeIfRequired(
                    loaded.chapters.first { it.chapter.id == canonicalChapterId },
                )
                applyDownloadResult(downloadCanonicalChapter.execute(resolved.id))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val state = _state.value as? CanonicalTitleScreenState.Loaded
                if (state != null) {
                    _state.value = state.copy(
                        downloadInProgressChapterId = null,
                        downloadError = error,
                    )
                }
            }
        }
        return downloadOperation
    }

    fun downloadSelectedOption(option: ContentOption): Job? {
        val loaded = _state.value as? CanonicalTitleScreenState.Loaded ?: return null
        val chapterId = loaded.downloadSelectionChapterId ?: option.canonicalChapterId
        if (option.canonicalChapterId != chapterId) return null

        downloadOperation?.cancel()
        _state.value = loaded.copy(
            downloadInProgressChapterId = chapterId,
            downloadSelectionChapterId = null,
            downloadError = null,
        )
        downloadOperation = viewModelScope.launch {
            val result = downloadCanonicalChapter.execute(
                canonicalChapterId = chapterId,
                selectedOption = option,
            )
            applyDownloadResult(result)
        }
        return downloadOperation
    }

    fun dismissDownloadSelector() {
        val loaded = _state.value as? CanonicalTitleScreenState.Loaded ?: return
        _state.value = loaded.copy(downloadSelectionChapterId = null)
    }

    private suspend fun loadCachedFirst(canonicalTitleId: String) {
        val initialStart = TimeSource.Monotonic.markNow()
        _state.value = CanonicalTitleScreenState.Loading
        try {
            // Render only local/cached state first. Network/provider refresh must
            // never be on the critical path to opening a title.
            _state.value = loadLocalState(
                canonicalTitleId = canonicalTitleId,
                includeIntegrationMetadata = false,
                allowSourceNetwork = false,
                isRefreshing = true,
            )
            val cachedIntegrationMetadata = try {
                resolveCanonicalMetadata.cached(canonicalTitleId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
            if (cachedIntegrationMetadata != null && this.canonicalTitleId == canonicalTitleId) {
                val cachedState = loadLocalState(
                    canonicalTitleId = canonicalTitleId,
                    includeIntegrationMetadata = true,
                    integrationMetadataOverride = cachedIntegrationMetadata,
                    allowSourceNetwork = false,
                    isRefreshing = true,
                )
                val current = (_state.value as? CanonicalTitleScreenState.Loaded)
                    ?.takeIf { it.title.id == canonicalTitleId }
                _state.value = if (current == null) {
                    cachedState
                } else {
                    cachedState.copy(
                        libraryEntry = current.libraryEntry,
                        libraryMutationInProgress = current.libraryMutationInProgress,
                        libraryMutationError = current.libraryMutationError,
                        downloadInProgressChapterId = current.downloadInProgressChapterId,
                        downloadSelectionChapterId = current.downloadSelectionChapterId,
                        downloadError = current.downloadError,
                        chapterActionError = current.chapterActionError,
                    )
                }
            }
            logcat {
                "TsuzukiPerf detail cached chapters=" +
                    "${(_state.value as? CanonicalTitleScreenState.Loaded)?.chapters?.size ?: 0} " +
                    "metadata=${cachedIntegrationMetadata != null} " +
                    "elapsed=${initialStart.elapsedNow()}"
            }
            refreshInBackground(canonicalTitleId, forceChapterRefresh = false)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            _state.value = CanonicalTitleScreenState.Error(error)
        }
    }

    private suspend fun refreshInBackground(
        canonicalTitleId: String,
        forceChapterRefresh: Boolean,
    ) {
        val refreshStart = TimeSource.Monotonic.markNow()
        val before = _state.value as? CanonicalTitleScreenState.Loaded
        if (before != null) {
            _state.value = before.copy(isRefreshing = true, refreshError = null)
        }

        val refreshResults = coroutineScope {
            val chapterRefresh = async {
                refreshChapterEvidence.executeProgressively(
                    canonicalTitleId = canonicalTitleId,
                    forceRefresh = forceChapterRefresh,
                    onStageReconciled = {
                        publishChapterRefreshStage(canonicalTitleId)
                    },
                ).exceptionOrNull()
            }
            val metadataRefresh = async {
                resolveCanonicalMetadata.execute(
                    canonicalTitleId = canonicalTitleId,
                    forceRefresh = forceChapterRefresh,
                )
            }

            val metadataResult = metadataRefresh.await()
            val metadataElapsed = refreshStart.elapsedNow()
            val current = _state.value as? CanonicalTitleScreenState.Loaded
            if (current?.title?.id == canonicalTitleId) {
                val refreshedCounts = reportedChapterCountRepository.getByTitle(canonicalTitleId)
                _state.value = current.copy(
                    reportedChapterCounts = refreshedCounts,
                    chapters = withMetadataSlots(
                        canonicalTitleId,
                        current.chapters.filterNot(CanonicalChapterDetailItem::inferredFromCount),
                        refreshedCounts,
                    ),
                    refreshError = metadataResult.exceptionOrNull(),
                )
            }

            val chapterError = chapterRefresh.await()
            logcat {
                "TsuzukiPerf detail refresh metadataElapsed=$metadataElapsed " +
                    "chapterElapsed=${refreshStart.elapsedNow()} " +
                    "metadataError=${metadataResult.isFailure} chapterError=${chapterError != null}"
            }
            metadataResult to chapterError
        }

        try {
            val refreshed = loadLocalState(
                canonicalTitleId = canonicalTitleId,
                includeIntegrationMetadata = true,
                integrationMetadataOverride = refreshResults.first.getOrNull(),
                allowSourceNetwork = false,
                isRefreshing = false,
                refreshError = refreshResults.first.exceptionOrNull() ?: refreshResults.second,
            )
            logcat {
                "TsuzukiPerf detail ready chapters=${refreshed.chapters.size} " +
                    "elapsed=${refreshStart.elapsedNow()}"
            }
            val current = _state.value as? CanonicalTitleScreenState.Loaded
            _state.value = if (current == null || current.title.id != canonicalTitleId) {
                refreshed
            } else {
                refreshed.copy(
                    libraryEntry = current.libraryEntry,
                    libraryMutationInProgress = current.libraryMutationInProgress,
                    libraryMutationError = current.libraryMutationError,
                    downloadInProgressChapterId = current.downloadInProgressChapterId,
                    downloadSelectionChapterId = current.downloadSelectionChapterId,
                    downloadError = current.downloadError,
                    chapterActionError = current.chapterActionError,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val current = _state.value as? CanonicalTitleScreenState.Loaded
            _state.value = current?.copy(
                isRefreshing = false,
                refreshError = error,
            ) ?: CanonicalTitleScreenState.Error(error)
        }
    }

    private suspend fun publishChapterRefreshStage(canonicalTitleId: String) {
        val current = (_state.value as? CanonicalTitleScreenState.Loaded)
            ?.takeIf { it.title.id == canonicalTitleId }
            ?: return
        val staged = loadLocalState(
            canonicalTitleId = canonicalTitleId,
            includeIntegrationMetadata = false,
            allowSourceNetwork = false,
            isRefreshing = true,
        )
        val latest = (_state.value as? CanonicalTitleScreenState.Loaded)
            ?.takeIf { it.title.id == canonicalTitleId }
            ?: return
        _state.value = latest.copy(
            chapters = staged.chapters,
            addonCoverage = staged.addonCoverage,
            isRefreshing = true,
            refreshError = current.refreshError,
        )
    }

    private suspend fun loadLocalState(
        canonicalTitleId: String,
        includeIntegrationMetadata: Boolean,
        integrationMetadataOverride: ResolvedMetadata? = null,
        allowSourceNetwork: Boolean = includeIntegrationMetadata,
        isRefreshing: Boolean,
        refreshError: Throwable? = null,
    ): CanonicalTitleScreenState.Loaded {
        val title = canonicalTitleRepository.getById(canonicalTitleId)
            ?: throw NoSuchElementException("Canonical title not found: $canonicalTitleId")
        val libraryEntry = canonicalLibraryRepository.get(canonicalTitleId)
        val chapters = canonicalChapterRepository.getByCanonicalTitleId(canonicalTitleId)
        val supportSnapshot = chapterEvidenceRepository.getSupportSnapshot(canonicalTitleId)
        val supportedChapterIds = supportSnapshot.mappedCanonicalChapterIds
        val addonNames = try {
            addonRepository.snapshot().associate { it.id.value to it.displayName }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            emptyMap()
        }
        val progressByChapter = canonicalReadingRepository
            .getProgressByCanonicalTitleId(canonicalTitleId)
            .associateBy(CanonicalChapterProgress::canonicalChapterId)
        val canonicalDownloadIds = canonicalDownloadRepository
            .getChapterIdsByCanonicalTitle(canonicalTitleId)
        val reportedCounts = reportedChapterCountRepository.getByTitle(canonicalTitleId)
        val canonicalArtwork = try {
            resolveCanonicalArtwork.execute(canonicalTitleId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
        var metadata = try {
            resolveCanonicalSourceManga.execute(
                canonicalTitleId = canonicalTitleId,
                allowNetwork = allowSourceNetwork,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
        if (metadata == null) {
            val metadataSources = sourceTitleMappingRepository
                ?.getByCanonicalTitleId(canonicalTitleId)
                .orEmpty()
                .sortedByDescending { it.preferredOverride }
            for (source in metadataSources) {
                val manga = try {
                    source.mihonMangaId
                        ?.let { mangaRepository?.getMangaById(it) }
                        ?: mangaRepository?.getMangaByUrlAndSourceId(source.sourceUrl, source.sourceId)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    null
                }
                if (
                    manga != null &&
                    (
                        !manga.thumbnailUrl.isNullOrBlank() ||
                            !manga.description.isNullOrBlank() ||
                            !manga.author.isNullOrBlank()
                        )
                ) {
                    metadata = manga
                    break
                }
            }
        }

        val integrationMetadata = if (includeIntegrationMetadata) {
            integrationMetadataOverride ?: try {
                resolveCanonicalMetadata
                    .execute(canonicalTitleId)
                    .getOrNull()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
        val resolvedRatings = integrationMetadata
            ?.ratings
            .orEmpty()
            .ifEmpty { listOfNotNull(integrationMetadata?.ratingDetails) }
        val integrationRatings = resolvedRatings.map { rating ->
            CanonicalProviderRating(
                providerId = rating.providerId.value,
                value = rating.value.value,
                maxValue = rating.value.maxValue,
                voteCount = rating.value.voteCount,
            )
        }
        val integrationMetadataSources = buildList {
            addAll(
                listOfNotNull(
                    integrationMetadata?.title?.let { it.attribution ?: it.providerId.value },
                    integrationMetadata?.synopsis?.let { it.attribution ?: it.providerId.value },
                    integrationMetadata?.artworkUrl?.let { it.attribution ?: it.providerId.value },
                    integrationMetadata?.authors?.let { it.attribution ?: it.providerId.value },
                    integrationMetadata?.artists?.let { it.attribution ?: it.providerId.value },
                    integrationMetadata?.genres?.let { it.attribution ?: it.providerId.value },
                    integrationMetadata?.tags?.let { it.attribution ?: it.providerId.value },
                    integrationMetadata?.status?.let { it.attribution ?: it.providerId.value },
                    integrationMetadata?.format?.let { it.attribution ?: it.providerId.value },
                    integrationMetadata?.startDate?.let { it.attribution ?: it.providerId.value },
                    integrationMetadata?.endDate?.let { it.attribution ?: it.providerId.value },
                    integrationMetadata?.editorialVolumeCount?.let {
                        it.attribution ?: it.providerId.value
                    },
                ),
            )
            resolvedRatings.forEach { rating ->
                add(rating.attribution ?: rating.providerId.value)
            }
        }.distinct()

        val details = chapters.mapNotNull { chapter ->
            val progress = progressByChapter[chapter.id]
            val downloaded = chapter.id in canonicalDownloadIds
            if (
                chapter.confirmation != CanonicalChapterConfirmation.CONFIRMED &&
                chapter.id !in supportedChapterIds &&
                progress == null &&
                !downloaded
            ) {
                null
            } else {
                CanonicalChapterDetailItem(
                    chapter = chapter,
                    progress = progress,
                    downloaded = downloaded,
                )
            }
        }

        val resolvedProviderCover = integrationMetadata?.artworkUrl?.value ?: canonicalArtwork?.coverUrl
        logcat {
            "TsuzukiCover detail title=${canonicalTitleId.take(8)} " +
                "integrationRequested=$includeIntegrationMetadata " +
                "provider=${!resolvedProviderCover.isNullOrBlank()} " +
                "source=${metadata != null && !metadata.thumbnailUrl.isNullOrBlank()}"
        }

        val loaded = CanonicalTitleScreenState.Loaded(
            title = title,
            libraryEntry = libraryEntry,
            chapters = withMetadataSlots(canonicalTitleId, details, reportedCounts),
            reportedChapterCounts = reportedCounts,
            addonCoverage = observedAddonCoverage(chapters, supportSnapshot.addonMappedChapterIds, addonNames),
            coverUrl = integrationMetadata?.artworkUrl?.value ?: canonicalArtwork?.coverUrl,
            sourceCover = metadata?.asMangaCover(),
            author = integrationMetadata
                ?.authors
                ?.value
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString()
                ?: integrationMetadata
                    ?.artists
                    ?.value
                    ?.takeIf { it.isNotEmpty() }
                    ?.joinToString()
                ?: metadata?.author,
            description = integrationMetadata?.synopsis?.value ?: metadata?.description,
            genres = integrationMetadata?.genres?.value ?: metadata?.genre.orEmpty(),
            tags = integrationMetadata?.tags?.value.orEmpty(),
            editorialStatus = integrationMetadata?.status?.value,
            editorialFormat = integrationMetadata?.format?.value,
            ratingValue = integrationRatings.firstOrNull()?.value,
            ratingMaxValue = integrationRatings.firstOrNull()?.maxValue,
            ratingVoteCount = integrationRatings.firstOrNull()?.voteCount,
            ratings = integrationRatings,
            tsuzukiRating = integrationMetadata?.tsuzukiRating,
            startDate = integrationMetadata?.startDate?.value,
            endDate = integrationMetadata?.endDate?.value,
            editorialVolumeCount = integrationMetadata?.editorialVolumeCount?.value,
            metadataSources = integrationMetadataSources,
            isRefreshing = isRefreshing,
            refreshError = refreshError,
        )
        val cacheReason = if (isRefreshing) {
            ChapterInventoryDiagnosticReason.CACHE_SNAPSHOT
        } else {
            ChapterInventoryDiagnosticReason.REFRESHED_SNAPSHOT
        }
        recordUiDiagnosticSnapshot(
            state = loaded,
            reason = cacheReason,
            received = chapters.size,
        )
        return loaded.copy(
            chapterDiagnosticsRecording = diagnosticsRecording(canonicalTitleId),
            chapterDiagnosticReportAvailable = diagnosticReportAvailable(canonicalTitleId),
        )
    }

    private fun recordUiDiagnosticSnapshot(
        state: CanonicalTitleScreenState.Loaded,
        reason: ChapterInventoryDiagnosticReason,
        received: Int,
    ) {
        val realRows = state.chapters.filterNot(CanonicalChapterDetailItem::inferredFromCount)
        val provisional = realRows.count {
            it.confirmation == CanonicalChapterConfirmation.PROVISIONAL
        }
        val conflicted = realRows.count {
            it.confirmation == CanonicalChapterConfirmation.CONFLICTED
        }
        val inferred = state.chapters.count(CanonicalChapterDetailItem::inferredFromCount)
        val discarded = (received - realRows.size).coerceAtLeast(0)
        val labels = state.chapters.mapNotNull { item ->
            ChapterInventoryDiagnosticLabels.fromIdentity(item.chapter.identity)
        }
        val (boundaryLabels, gaps) = ChapterInventoryDiagnosticLabels.boundariesAndGaps(labels)
        val outcome = when {
            received == 0 && state.chapters.isEmpty() -> ChapterInventoryDiagnosticOutcome.EMPTY
            provisional > 0 || conflicted > 0 || inferred > 0 || discarded > 0 ->
                ChapterInventoryDiagnosticOutcome.PARTIAL
            else -> ChapterInventoryDiagnosticOutcome.SUCCESS
        }
        val reasons = buildMap {
            put(reason, 1)
            if (provisional > 0) put(ChapterInventoryDiagnosticReason.LOW_CONFIDENCE, provisional)
            if (conflicted > 0) put(ChapterInventoryDiagnosticReason.IDENTITY_MISMATCH, conflicted)
            if (discarded > 0) put(ChapterInventoryDiagnosticReason.FILTERED_FROM_UI, discarded)
        }
        diagnostics.recordIfEnabled(
            state.title.id,
            ChapterInventoryDiagnosticEvent(
                stage = ChapterInventoryDiagnosticStage.UI,
                outcome = outcome,
                received = received.coerceAtLeast(0),
                accepted = realRows.size,
                provisional = provisional,
                discarded = discarded,
                inferred = inferred,
                labels = boundaryLabels,
                gaps = gaps,
                reasons = reasons,
            ),
        )
    }

    private fun diagnosticsRecording(canonicalTitleId: String): Boolean = try {
        diagnostics.isRecording(canonicalTitleId)
    } catch (_: Exception) {
        false
    }

    private fun updateDiagnosticUiState(canonicalTitleId: String) {
        val loaded = _state.value as? CanonicalTitleScreenState.Loaded ?: return
        if (loaded.title.id != canonicalTitleId) return
        _state.value = loaded.copy(
            chapterDiagnosticsRecording = diagnosticsRecording(canonicalTitleId),
            chapterDiagnosticReportAvailable = diagnosticReportAvailable(canonicalTitleId),
        )
    }

    private fun diagnosticReportAvailable(canonicalTitleId: String): Boolean =
        diagnosticTitleId == canonicalTitleId && chapterDiagnosticReport().isNotBlank()

    private fun withMetadataSlots(
        titleId: String,
        realRows: List<CanonicalChapterDetailItem>,
        reportedCounts: List<ReportedChapterCount>,
    ): List<CanonicalChapterDetailItem> {
        val byId = realRows.associateBy { it.chapter.id }
        return buildCanonicalChapterOutline(titleId, realRows.map { it.chapter }, reportedCounts)
            .map { entry ->
                byId[entry.chapter.id] ?: CanonicalChapterDetailItem(
                    chapter = entry.chapter,
                    progress = null,
                    downloaded = false,
                    inferredFromCount = entry.inferredFromReportedCount,
                )
            }
    }

    private fun applyDownloadResult(result: CanonicalDownloadPreparation) {
        val loaded = _state.value as? CanonicalTitleScreenState.Loaded ?: return
        _state.value = when (result) {
            is CanonicalDownloadPreparation.Complete -> loaded.copy(
                chapters = loaded.chapters.map { item ->
                    if (item.chapter.id == result.canonicalChapterId) {
                        item.copy(downloaded = true)
                    } else {
                        item
                    }
                },
                downloadInProgressChapterId = null,
                downloadSelectionChapterId = null,
                downloadError = null,
            )
            is CanonicalDownloadPreparation.SelectionRequired -> loaded.copy(
                downloadInProgressChapterId = null,
                downloadSelectionChapterId = result.canonicalChapterId,
                downloadError = null,
            )
            is CanonicalDownloadPreparation.Unavailable -> loaded.copy(
                downloadInProgressChapterId = null,
                downloadSelectionChapterId = null,
                downloadError = IllegalStateException("No content option is available for this chapter."),
            )
            is CanonicalDownloadPreparation.Failed -> loaded.copy(
                downloadInProgressChapterId = null,
                downloadSelectionChapterId = null,
                downloadError = result.error,
            )
        }
    }

    private fun updateLibraryState(entry: CanonicalLibraryEntry?) {
        val loaded = _state.value as? CanonicalTitleScreenState.Loaded ?: return
        _state.value = loaded.copy(
            libraryEntry = entry,
            libraryMutationInProgress = false,
            libraryMutationError = null,
        )
    }

    private fun updateLibraryError(error: Throwable) {
        val loaded = _state.value as? CanonicalTitleScreenState.Loaded ?: return
        _state.value = loaded.copy(
            libraryMutationInProgress = false,
            libraryMutationError = error,
        )
    }

    private companion object {
    }
}
