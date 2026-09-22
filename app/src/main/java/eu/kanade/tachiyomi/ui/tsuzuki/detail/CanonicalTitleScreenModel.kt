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
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.evidence.RefreshChapterEvidence
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
        val isRefreshing: Boolean = false,
        val refreshError: Throwable? = null,
        val libraryMutationInProgress: Boolean = false,
        val libraryMutationError: Throwable? = null,
        val downloadInProgressChapterId: String? = null,
        val downloadSelectionChapterId: String? = null,
        val downloadError: Throwable? = null,
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
    private val chapterEvidenceRepository: ChapterEvidenceRepository,
    private val canonicalReadingRepository: CanonicalReadingRepository,
    private val getCanonicalChapterDownloadState: GetCanonicalChapterDownloadState,
    private val downloadCanonicalChapter: DownloadCanonicalChapter,
    private val canonicalDownloadRepository: CanonicalDownloadRepository,
    private val reportedChapterCountRepository: ReportedChapterCountRepository,
    private val refreshReportedChapterCounts: RefreshReportedChapterCounts,
    private val refreshChapterEvidence: RefreshChapterEvidence,
) : ViewModel() {

    private val _state = MutableStateFlow<CanonicalTitleScreenState>(CanonicalTitleScreenState.Loading)
    val state: StateFlow<CanonicalTitleScreenState> = _state.asStateFlow()

    private var canonicalTitleId: String? = null
    private var operation: Job? = null
    private var downloadOperation: Job? = null

    fun start(canonicalTitleId: String): Job {
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
            val result = downloadCanonicalChapter.execute(canonicalChapterId)
            applyDownloadResult(result)
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
                _state.value = current.copy(
                    reportedChapterCounts = reportedChapterCountRepository.getByTitle(canonicalTitleId),
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
        val supportedChapterIds = chapterEvidenceRepository
            .getByCanonicalTitleId(canonicalTitleId)
            .mapNotNull { it.mappedCanonicalChapterId }
            .toSet()
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

        return CanonicalTitleScreenState.Loaded(
            title = title,
            libraryEntry = libraryEntry,
            chapters = details,
            reportedChapterCounts = reportedCounts,
            isRefreshing = isRefreshing,
            refreshError = refreshError,
        )
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
