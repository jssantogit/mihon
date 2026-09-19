package tachiyomi.domain.tsuzuki.chapter.model

/** Confidence attached to a gap observation, separate from canonical-structure certainty. */
enum class CanonicalChapterGapCertainty {
    OBSERVED,
    STRUCTURAL_UNCERTAIN,
}

enum class CanonicalChapterGapEvidence {
    MISSING_VARIANT_ON_MAPPING,
}

/**
 * A missing source variant for one known canonical REGULAR chapter.
 *
 * UNKNOWN chapters never become gaps. Structural uncertainty is retained so
 * callers can distinguish an observed missing variant from authoritative
 * knowledge that the chapter must exist.
 */
data class CanonicalChapterGap(
    val chapter: CanonicalChapter,
    val sourceMappingId: String,
    val evidence: Set<CanonicalChapterGapEvidence> = setOf(
        CanonicalChapterGapEvidence.MISSING_VARIANT_ON_MAPPING,
    ),
    val structuralUncertainty: Set<ChapterStructureUncertainty> = emptySet(),
) {
    val canonicalChapterId: String
        get() = chapter.id

    val certainty: CanonicalChapterGapCertainty
        get() = if (structuralUncertainty.isEmpty()) {
            CanonicalChapterGapCertainty.OBSERVED
        } else {
            CanonicalChapterGapCertainty.STRUCTURAL_UNCERTAIN
        }
}
