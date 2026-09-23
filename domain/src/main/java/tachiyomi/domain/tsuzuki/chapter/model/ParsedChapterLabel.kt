package tachiyomi.domain.tsuzuki.chapter.model

/**
 * The conservative interpretation of one source chapter label.
 *
 * [rawLabel] is never normalized or discarded. [displayNumber] is a separate
 * presentation value and [identity] contains only structured reconciliation
 * components.
 */
data class ParsedChapterLabel(
    val rawLabel: String,
    val displayNumber: String,
    val type: CanonicalChapterType,
    val baseNumber: Int?,
    val part: Int?,
    val alphaSuffix: String?,
    val confidence: Double,
    val identity: CanonicalChapterIdentity = CanonicalChapterIdentity(
        type = type,
        baseNumber = baseNumber,
        part = part,
        alphaSuffix = alphaSuffix,
    ),
    val sortKey: String = identity.sortKey,
    /** A source-provided numeric hint retained as text, never as identity. */
    val numericHint: String? = null,
) {

    val rawSourceLabel: String
        get() = rawLabel

    val displayLabel: String
        get() = displayNumber

    val chapterNumber: Int?
        get() = baseNumber

    val suffix: String?
        get() = alphaSuffix

    val canonicalIdentity: CanonicalChapterIdentity
        get() = identity
}
