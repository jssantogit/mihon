package tachiyomi.domain.tsuzuki.chapter.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterGap
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterStructureUncertainty
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository

@Inject
class DetectCanonicalChapterGaps(
    private val canonicalChapterRepository: CanonicalChapterRepository,
) {

    suspend fun execute(
        canonicalTitleId: String,
        sourceMappingId: String,
    ): List<CanonicalChapterGap> {
        val chapters = canonicalChapterRepository.getByCanonicalTitleId(canonicalTitleId)
        val availableIds = canonicalChapterRepository
            .getVariantsBySourceMappingId(sourceMappingId)
            .mapTo(mutableSetOf()) { it.canonicalChapterId }
        val containsUnknown = chapters.any { it.type == CanonicalChapterType.UNKNOWN }

        return chapters
            .asSequence()
            .filter { it.type == CanonicalChapterType.REGULAR }
            .filter { it.id !in availableIds }
            .sortedBy { it.sortKey }
            .map { chapter ->
                CanonicalChapterGap(
                    chapter = chapter,
                    sourceMappingId = sourceMappingId,
                    structuralUncertainty = buildSet {
                        add(ChapterStructureUncertainty.SOURCE_DERIVED)
                        if (containsUnknown) {
                            add(ChapterStructureUncertainty.UNKNOWN_CHAPTERS)
                        }
                        if (chapter.confidence < LOW_CONFIDENCE_THRESHOLD) {
                            add(ChapterStructureUncertainty.LOW_CONFIDENCE)
                        }
                    },
                )
            }
            .toList()
    }

    suspend operator fun invoke(
        canonicalTitleId: String,
        sourceMappingId: String,
    ): List<CanonicalChapterGap> = execute(canonicalTitleId, sourceMappingId)

    private companion object {
        const val LOW_CONFIDENCE_THRESHOLD = 0.90
    }
}
