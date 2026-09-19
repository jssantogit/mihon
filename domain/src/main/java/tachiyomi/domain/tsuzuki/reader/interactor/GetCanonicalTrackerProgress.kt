package tachiyomi.domain.tsuzuki.reader.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.reader.model.CanonicalTrackerProgress

@Inject
class GetCanonicalTrackerProgress(
    private val canonicalChapterRepository: CanonicalChapterRepository,
) {

    suspend fun execute(canonicalChapterId: String): CanonicalTrackerProgress? {
        val chapter = canonicalChapterRepository.getById(canonicalChapterId) ?: return null

        // Conservative MVP translation:
        // only whole REGULAR chapters advance a tracker's numeric counter.
        // Decimal/part/suffix semantics differ across sources and trackers, so
        // they must not fabricate progress until a tracker-aware translator exists.
        if (
            chapter.type != CanonicalChapterType.REGULAR ||
            chapter.baseNumber == null ||
            chapter.part != null ||
            chapter.alphaSuffix != null
        ) {
            return null
        }

        return CanonicalTrackerProgress(
            canonicalTitleId = chapter.canonicalTitleId,
            canonicalChapterId = chapter.id,
            chapterNumber = chapter.baseNumber.toDouble(),
        )
    }
}
