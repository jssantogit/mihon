package tachiyomi.domain.tsuzuki.home.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.chapter.update.repository.ChapterUpdateStateRepository
import tachiyomi.domain.tsuzuki.home.model.ContinueReadingVisibility
import tachiyomi.domain.tsuzuki.home.model.HomeContinueReadingItem
import tachiyomi.domain.tsuzuki.home.repository.ContinueReadingVisibilityRepository
import tachiyomi.domain.tsuzuki.library.interactor.ObserveCanonicalLibrary
import tachiyomi.domain.tsuzuki.library.model.CanonicalLibraryItem
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository

@Inject
class ObserveHomeContinueReading(
    private val observeCanonicalLibrary: ObserveCanonicalLibrary,
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val canonicalReadingRepository: CanonicalReadingRepository,
    private val visibilityRepository: ContinueReadingVisibilityRepository,
    private val chapterUpdateStateRepository: ChapterUpdateStateRepository,
) {

    fun subscribe(limit: Int = 10): Flow<List<HomeContinueReadingItem>> {
        require(limit > 0) { "Continue Reading limit must be positive" }

        return combine(
            observeCanonicalLibrary.subscribe(),
            visibilityRepository.observeAll(),
        ) { libraryItems, visibility ->
            libraryItems to visibility.associateBy(ContinueReadingVisibility::canonicalTitleId)
        }.flatMapLatest { (libraryItems, visibilityByTitle) ->
            if (libraryItems.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    libraryItems.map { item ->
                        observeItem(
                            libraryItem = item,
                            visibility = visibilityByTitle[item.title.id],
                        )
                    },
                ) { candidates ->
                    candidates
                        .filterNotNull()
                        .sortedByDescending { it.updatedAt }
                        .take(limit)
                }
            }
        }
    }

    private fun observeItem(
        libraryItem: CanonicalLibraryItem,
        visibility: ContinueReadingVisibility?,
    ): Flow<HomeContinueReadingItem?> {
        val canonicalTitleId = libraryItem.title.id
        return combine(
            canonicalChapterRepository.observeByCanonicalTitleId(canonicalTitleId),
            canonicalReadingRepository.observeProgressByCanonicalTitleId(canonicalTitleId),
            chapterUpdateStateRepository.observeByTitle(canonicalTitleId),
        ) { chapters, progressItems, updateStates ->
            val chapterById = chapters.associateBy { it.id }
            val progress = progressItems
                .filter(::isContinueReadingProgress)
                .maxWithOrNull(
                    compareBy<CanonicalChapterProgress> { it.updatedAt }
                        .thenBy { chapterById[it.canonicalChapterId]?.sortKey.orEmpty() },
                )
                ?: return@combine null

            val hiddenAt = visibility?.hiddenAt
            if (hiddenAt != null && progress.updatedAt <= hiddenAt) {
                return@combine null
            }

            val chapter = chapterById[progress.canonicalChapterId]
                ?: return@combine null

            HomeContinueReadingItem(
                canonicalTitleId = canonicalTitleId,
                title = libraryItem.title.displayTitle,
                canonicalChapterId = chapter.id,
                chapterDisplayNumber = chapter.displayNumber,
                lastPageRead = progress.lastPageRead,
                updatedAt = progress.updatedAt,
                newChapterCount = updateStates.count { it.acknowledgedAt == null },
            )
        }
    }

    private fun isContinueReadingProgress(progress: CanonicalChapterProgress): Boolean {
        if (progress.read || progress.updatedAt <= 0L) return false
        return progress.lastPageRead > 0L || progress.lastVariantId != null
    }
}
