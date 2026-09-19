package tachiyomi.domain.tsuzuki.chapter.model

/**
 * Deterministic ranking result for the variants of one canonical chapter.
 *
 * The result is read-only: selection never mutates title-level source
 * preferences.
 */
data class ChapterVariantSelection(
    val canonicalChapterId: String,
    val preferredLanguage: String?,
    val selected: ChapterVariant?,
    val candidates: List<ChapterVariant>,
    val usedPreferredLanguage: Boolean = false,
    val usedPreferredMapping: Boolean = false,
    val preferredSourceMappingId: String? = null,
    val requiresFallback: Boolean = false,
) {
    val selectedVariant: ChapterVariant?
        get() = selected

    val hasCandidate: Boolean
        get() = selected != null
}
