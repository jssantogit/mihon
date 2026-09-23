package tachiyomi.domain.tsuzuki.home.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import tachiyomi.domain.tsuzuki.chapter.update.repository.ChapterUpdateStateRepository
import tachiyomi.domain.tsuzuki.home.model.ContinueReadingVisibility
import tachiyomi.domain.tsuzuki.home.model.HomeContinueReadingItem
import tachiyomi.domain.tsuzuki.home.model.HomeContinueReadingSeed
import tachiyomi.domain.tsuzuki.home.repository.ContinueReadingVisibilityRepository
import tachiyomi.domain.tsuzuki.home.repository.HomeContinueReadingSource

@Inject
class ObserveHomeContinueReading(
    private val source: HomeContinueReadingSource,
    private val visibilityRepository: ContinueReadingVisibilityRepository,
    private val chapterUpdateStateRepository: ChapterUpdateStateRepository,
) {

    fun subscribe(limit: Int = 10): Flow<List<HomeContinueReadingItem>> {
        require(limit > 0) { "Continue Reading limit must be positive" }

        return combine(
            source.observe(),
            visibilityRepository.observeAll(),
        ) { progress, visibility ->
            progress to visibility.associateBy(ContinueReadingVisibility::canonicalTitleId)
        }.flatMapLatest { (progress, visibilityByTitle) ->
            val progressByTitle = progress.groupBy(HomeContinueReadingSeed::canonicalTitleId)
            val candidates = progressByTitle.mapNotNull { (titleId, titleProgress) ->
                val candidate = titleProgress
                    .filter(::isContinueReadingProgress)
                    .maxByOrNull(HomeContinueReadingSeed::updatedAt)
                    ?: return@mapNotNull null

                val hiddenAt = visibilityByTitle[titleId]?.hiddenAt
                if (hiddenAt != null && candidate.updatedAt <= hiddenAt) {
                    return@mapNotNull null
                }
                candidate
            }

            if (candidates.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    candidates.map { candidate ->
                        observeItem(
                            candidate = candidate,
                            titleProgress = progressByTitle[candidate.canonicalTitleId].orEmpty(),
                        )
                    },
                ) { items ->
                    items
                        .sortedByDescending(HomeContinueReadingItem::updatedAt)
                        .take(limit)
                }
            }
        }
    }

    private fun observeItem(
        candidate: HomeContinueReadingSeed,
        titleProgress: List<HomeContinueReadingSeed>,
    ): Flow<HomeContinueReadingItem> {
        return chapterUpdateStateRepository.observeByTitle(candidate.canonicalTitleId).map { updateStates ->
            HomeContinueReadingItem(
                canonicalTitleId = candidate.canonicalTitleId,
                title = candidate.title,
                canonicalChapterId = candidate.canonicalChapterId,
                chapterDisplayNumber = candidate.chapterDisplayNumber,
                lastPageRead = candidate.lastPageRead,
                updatedAt = candidate.updatedAt,
                newChapterCount = updateStates.count { state ->
                    state.acknowledgedAt == null &&
                        titleProgress.none { progress ->
                            progress.canonicalChapterId == state.canonicalChapterId && progress.read
                        }
                },
            )
        }
    }

    private fun isContinueReadingProgress(progress: HomeContinueReadingSeed): Boolean {
        if (progress.read || progress.updatedAt <= 0L) return false
        return progress.lastPageRead > 0L || progress.lastVariantId != null
    }
}
