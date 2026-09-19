package tachiyomi.domain.tsuzuki.chapter.model

/** Why the currently known canonical chapter structure is not fully authoritative. */
enum class ChapterStructureUncertainty {
    SOURCE_DERIVED,
    UNKNOWN_CHAPTERS,
    LOW_CONFIDENCE,
}

/** Regular-chapter coverage for one source mapping. */
data class ChapterCoverage(
    val canonicalTitleId: String,
    val sourceMappingId: String,
    val available: Int,
    val total: Int,
    val ratio: Double,
    val structuralUncertainty: Set<ChapterStructureUncertainty> = emptySet(),
) {
    val missing: Int
        get() = (total - available).coerceAtLeast(0)

    val isStructurallyCertain: Boolean
        get() = structuralUncertainty.isEmpty()
}
