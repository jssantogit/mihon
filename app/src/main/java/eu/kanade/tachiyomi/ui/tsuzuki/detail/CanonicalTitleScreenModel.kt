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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
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
                refreshInBackground(canonicalTitleId)
            }
        }
        return operation!!
    }

    fun refresh(): Job? {
        val id = canonicalTitleId ?: return null
        operation?.cancel()
        operation = viewModelScope.launch {
            refreshInBackground(id)
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
                includeLegacyDownloadChecks = false,
                isRefreshing = true,
            )
            logcat {
                "TsuzukiPerf detail cached chapters=" +
                    "${(_state.value as? CanonicalTitleScreenState.Loaded)?.chapters?.size ?: 0} " +
                    "elapsed=${initialStart.elapsedNow()}"
            }
            refreshInBackground(canonicalTitleId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            _state.value = CanonicalTitleScreenState.Error(error)
        }
    }

    private suspend fun refreshInBackground(canonicalTitleId: String) {
        val refreshStart = TimeSource.Monotonic.markNow()
        val before = _state.value as? CanonicalTitleScreenState.Loaded
        if (before != null) {
            _state.value = before.copy(isRefreshing = true, refreshError = null)
        }

        val errors = coroutineScope {
            val chapterRefresh = async {
                refreshChapterEvidence.execute(canonicalTitleId).exceptionOrNull()
            }
            val metadataRefresh = async {
                refreshReportedChapterCounts.execute(canonicalTitleId).exceptionOrNull()
            }

            val metadataError = metadataRefresh.await()
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
                    refreshError = metadataError,
                )
            }

            val chapterError = chapterRefresh.await()
            logcat {
                "TsuzukiPerf detail refresh metadataElapsed=$metadataElapsed " +
                    "chapterElapsed=${refreshStart.elapsedNow()} " +
                    "metadataError=${metadataError != null} chapterError=${chapterError != null}"
            }
            listOfNotNull(metadataError, chapterError)
        }

        try {
            val refreshed = loadLocalState(
                canonicalTitleId = canonicalTitleId,
                includeLegacyDownloadChecks = true,
                isRefreshing = false,
                refreshError = errors.firstOrNull(),
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

    private suspend fun loadLocalState(
        canonicalTitleId: String,
        includeLegacyDownloadChecks: Boolean,
        isRefreshing: Boolean,
        refreshError: Throwable? = null,
    ): CanonicalTitleScreenState.Loaded {
        val title = canonicalTitleRepository.getById(canonicalTitleId)
            ?: throw NoSuchElementException("Canonical title not found: $canonicalTitleId")
        val libraryEntry = canonicalLibraryRepository.get(canonicalTitleId)
        val chapters = canonicalChapterRepository.getByCanonicalTitleId(canonicalTitleId)
        val persistedEvidence = chapterEvidenceRepository.getByCanonicalTitleId(canonicalTitleId)
        val supportedChapterIds = persistedEvidence
            .mapNotNull { it.mappedCanonicalChapterId }
            .toSet()
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
            .getAll()
            .asSequence()
            .map { it.canonicalChapterId }
            .toSet()
        val reportedCounts = reportedChapterCountRepository.getByTitle(canonicalTitleId)

        val details = if (!includeLegacyDownloadChecks) {
            chapters.mapNotNull { chapter ->
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
        } else {
            coroutineScope {
                val downloadCheckGate = Semaphore(MAX_CONCURRENT_DOWNLOAD_CHECKS)
                chapters.map { chapter ->
                    async {
                        val progress = progressByChapter[chapter.id]
                        val downloaded = chapter.id in canonicalDownloadIds ||
                            downloadCheckGate.withPermit {
                                try {
                                    getCanonicalChapterDownloadState.execute(chapter.id).hasDownload
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Throwable) {
                                    false
                                }
                            }
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
                }.awaitAll().filterNotNull()
            }
        }

        val loaded = CanonicalTitleScreenState.Loaded(
            title = title,
            libraryEntry = libraryEntry,
            chapters = withMetadataSlots(canonicalTitleId, details, reportedCounts),
            reportedChapterCounts = reportedCounts,
            addonCoverage = observedAddonCoverage(chapters, persistedEvidence, addonNames),
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
        const val MAX_CONCURRENT_DOWNLOAD_CHECKS = 8
    }
}
