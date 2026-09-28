package tachiyomi.domain.tsuzuki.chapter.interactor

import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ParsedChapterLabel

/**
 * Treats Mihon's chapter number as a contradiction detector, never as canonical identity.
 *
 * Unknown/zero/decimal hints remain non-authoritative because extensions commonly use them
 * as placeholders. A positive integer that disagrees with an otherwise plain regular integer
 * label is strong evidence that the source row must fail closed.
 */
fun hasConflictingIntegerChapterHint(
    parsed: ParsedChapterLabel,
    rawNumberHint: Double?,
): Boolean {
    val hint = rawNumberHint ?: return false
    if (!hint.isFinite() || hint <= 0.0 || hint > Int.MAX_VALUE.toDouble() || hint % 1.0 != 0.0) {
        return false
    }
    val identity = parsed.identity
    if (
        identity.type != CanonicalChapterType.REGULAR ||
        identity.part != null ||
        identity.alphaSuffix != null
    ) {
        return false
    }
    val baseNumber = identity.baseNumber ?: return false
    return hint.toInt() != baseNumber
}
