package tachiyomi.domain.tsuzuki.home.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import tachiyomi.domain.tsuzuki.home.model.HomeContinueReadingItem
import tachiyomi.domain.tsuzuki.library.interactor.ObserveCanonicalLibrary
import tachiyomi.domain.tsuzuki.library.model.CanonicalLibraryItem
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository

@Inject
class ObserveHomeContinueReading(
    private val observeCanonicalLibrary: ObserveCanonicalLibrary,
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val canonicalReadingRepository: CanonicalReadingRepository,
) {

    fun subscribe(limit: Int = 10): Flow<List<HomeContinueReadingItem>> {
        return observeCanonicalLibrary.subscribe().flatMapLatest { libraryItems ->
            if (libraryItems.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    libraryItems.map(::observeItem),
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
    ): Flow<HomeContinueReadingItem?> {
        val canonicalTitleId = libraryItem.title.id
        return combine(
            canonicalChapterRepository.observeByCanonicalTitleId(canonicalTitleId),
            canonicalReadingRepository.observeProgressByCanonicalTitleId(canonicalTitleId),
        ) { chapters, progressItems ->
            val chapterById = chapters.associateBy { it.id }
            val progress = progressItems
                .filter { !it.read && (it.lastPageRead > 0L || it.updatedAt > 0L) }
                .maxWithOrNull(
                    compareBy<CanonicalChapterProgress> { it.updatedAt }
                        .thenBy { chapterById[it.canonicalChapterId]?.sortKey.orEmpty() },
                )
                ?: return@combine null
            val chapter = chapterById[progress.canonicalChapterId]
                ?: return@combine null

            HomeContinueReadingItem(
                canonicalTitleId = canonicalTitleId,
                title = libraryItem.title.displayTitle,
                canonicalChapterId = chapter.id,
                chapterDisplayNumber = chapter.displayNumber,
                lastPageRead = progress.lastPageRead,
                updatedAt = progress.updatedAt,
            )
        }
    }
}
