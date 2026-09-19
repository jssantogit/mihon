package tachiyomi.domain.tsuzuki.chapter.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterCoverage
import tachiyomi.domain.tsuzuki.chapter.model.ChapterStructureUncertainty
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository

@Inject
class CalculateChapterCoverage(
    private val canonicalChapterRepository: CanonicalChapterRepository,
) {

    suspend fun execute(
        canonicalTitleId: String,
        sourceMappingId: String,
    ): ChapterCoverage {
        val chapters = canonicalChapterRepository.getByCanonicalTitleId(canonicalTitleId)
        val regular = chapters.filter { it.type == CanonicalChapterType.REGULAR }
        val availableIds = canonicalChapterRepository
            .getVariantsBySourceMappingId(sourceMappingId)
            .mapTo(mutableSetOf()) { it.canonicalChapterId }

        val available = regular.count { it.id in availableIds }
        val total = regular.size
        val uncertainty = buildSet {
            // Milestone 6 canonical structure is inferred from reading sources.
            add(ChapterStructureUncertainty.SOURCE_DERIVED)
            if (chapters.any { it.type == CanonicalChapterType.UNKNOWN }) {
                add(ChapterStructureUncertainty.UNKNOWN_CHAPTERS)
            }
            if (regular.any { it.confidence < LOW_CONFIDENCE_THRESHOLD }) {
                add(ChapterStructureUncertainty.LOW_CONFIDENCE)
            }
        }

        return ChapterCoverage(
            canonicalTitleId = canonicalTitleId,
            sourceMappingId = sourceMappingId,
            available = available,
            total = total,
            ratio = if (total == 0) 0.0 else available.toDouble() / total,
            structuralUncertainty = uncertainty,
        )
    }

    suspend operator fun invoke(
        canonicalTitleId: String,
        sourceMappingId: String,
    ): ChapterCoverage = execute(canonicalTitleId, sourceMappingId)

    private companion object {
        const val LOW_CONFIDENCE_THRESHOLD = 0.90
    }
}
