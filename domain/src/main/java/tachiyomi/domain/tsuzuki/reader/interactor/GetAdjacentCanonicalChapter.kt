package tachiyomi.domain.tsuzuki.reader.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterDirection

@Inject
class GetAdjacentCanonicalChapter(
    private val canonicalChapterRepository: CanonicalChapterRepository,
) {

    suspend fun execute(
        canonicalChapterId: String,
        direction: CanonicalChapterDirection,
    ): CanonicalChapter? {
        val current = canonicalChapterRepository.getById(canonicalChapterId) ?: return null
        val chapters = canonicalChapterRepository.getByCanonicalTitleId(current.canonicalTitleId)
        val currentIndex = chapters.indexOfFirst { it.id == canonicalChapterId }
        if (currentIndex < 0) return null

        val targetIndex = when (direction) {
            CanonicalChapterDirection.PREVIOUS -> currentIndex - 1
            CanonicalChapterDirection.NEXT -> currentIndex + 1
        }
        return chapters.getOrNull(targetIndex)
    }

    suspend operator fun invoke(
        canonicalChapterId: String,
        direction: CanonicalChapterDirection,
    ): CanonicalChapter? = execute(canonicalChapterId, direction)
}
