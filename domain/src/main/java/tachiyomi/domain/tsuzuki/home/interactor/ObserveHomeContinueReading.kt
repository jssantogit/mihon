package tachiyomi.domain.tsuzuki.home.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.home.model.HomeContinueReadingItem
import tachiyomi.domain.tsuzuki.library.interactor.ObserveCanonicalLibrary
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository

@Inject
class ObserveHomeContinueReading(
    private val observeCanonicalLibrary: ObserveCanonicalLibrary,
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val canonicalReadingRepository: CanonicalReadingRepository,
) {

    fun subscribe(limit: Int = 10): Flow<List<HomeContinueReadingItem>> {
        return observeCanonicalLibrary.subscribe().flatMapLatest { libraryItems ->
            flow {
                val items = libraryItems.mapNotNull { libraryItem ->
                    val canonicalTitleId = libraryItem.title.id
                    val chapters = canonicalChapterRepository.getByCanonicalTitleId(canonicalTitleId)
                    if (chapters.isEmpty()) return@mapNotNull null
                    val chapterById = chapters.associateBy { it.id }
                    val progress = canonicalReadingRepository
                        .getProgressByCanonicalTitleId(canonicalTitleId)
                        .filter { !it.read && (it.lastPageRead > 0L || it.updatedAt > 0L) }
                        .maxWithOrNull(
                            compareBy<tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress> {
                                it.updatedAt
                            }.thenBy {
                                chapterById[it.canonicalChapterId]?.sortKey.orEmpty()
                            },
                        )
                        ?: return@mapNotNull null
                    val chapter = chapterById[progress.canonicalChapterId]
                        ?: return@mapNotNull null

                    HomeContinueReadingItem(
                        canonicalTitleId = canonicalTitleId,
                        title = libraryItem.title.displayTitle,
                        canonicalChapterId = chapter.id,
                        chapterDisplayNumber = chapter.displayNumber,
                        lastPageRead = progress.lastPageRead,
                        updatedAt = progress.updatedAt,
                    )
                }
                    .sortedByDescending { it.updatedAt }
                    .take(limit)

                emit(items)
            }
        }
    }
}
