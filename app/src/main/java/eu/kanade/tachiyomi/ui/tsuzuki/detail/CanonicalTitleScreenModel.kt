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
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.evidence.RefreshChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.download.interactor.GetCanonicalChapterDownloadState
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

@Immutable
sealed interface CanonicalTitleScreenState {
    data object Loading : CanonicalTitleScreenState

    data class Loaded(
        val title: CanonicalTitle,
        val libraryEntry: CanonicalLibraryEntry?,
        val chapters: List<CanonicalChapterDetailItem>,
        val refreshError: Throwable? = null,
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
    private val canonicalReadingRepository: CanonicalReadingRepository,
    private val getCanonicalChapterDownloadState: GetCanonicalChapterDownloadState,
    private val refreshChapterEvidence: RefreshChapterEvidence,
) : ViewModel() {

    private val _state =
        MutableStateFlow<CanonicalTitleScreenState>(
            CanonicalTitleScreenState.Loading,
        )
    val state: StateFlow<CanonicalTitleScreenState> = _state.asStateFlow()

    private var canonicalTitleId: String? = null
    private var operation: Job? = null

    fun start(canonicalTitleId: String): Job {
        this.canonicalTitleId = canonicalTitleId
        operation?.cancel()
        operation = viewModelScope.launch {
            load(canonicalTitleId)
        }
        return operation!!
    }

    fun refresh(): Job? {
        val id = canonicalTitleId ?: return null
        return start(id)
    }

    private suspend fun load(canonicalTitleId: String) {
        _state.value = CanonicalTitleScreenState.Loading
        try {
            val refreshError = refreshChapterEvidence
                .execute(canonicalTitleId)
                .exceptionOrNull()
            val title = canonicalTitleRepository.getById(canonicalTitleId)
                ?: throw NoSuchElementException(
                    "Canonical title not found: $canonicalTitleId",
                )
            val libraryEntry =
                canonicalLibraryRepository.get(canonicalTitleId)
            val chapters =
                canonicalChapterRepository.getByCanonicalTitleId(
                    canonicalTitleId,
                )
            val details = coroutineScope {
                chapters.map { chapter ->
                    async {
                        val progress =
                            canonicalReadingRepository.getProgress(chapter.id)
                        val downloaded = runCatching {
                            getCanonicalChapterDownloadState
                                .execute(chapter.id)
                                .hasDownload
                        }.getOrDefault(false)
                        CanonicalChapterDetailItem(
                            chapter = chapter,
                            progress = progress,
                            downloaded = downloaded,
                        )
                    }
                }.awaitAll()
            }

            _state.value = CanonicalTitleScreenState.Loaded(
                title = title,
                libraryEntry = libraryEntry,
                chapters = details,
                refreshError = refreshError,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            _state.value = CanonicalTitleScreenState.Error(error)
        }
    }
}
