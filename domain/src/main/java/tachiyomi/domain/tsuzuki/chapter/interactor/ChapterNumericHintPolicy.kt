package tachiyomi.domain.tsuzuki.chapter.interactor

import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ParsedChapterLabel
import kotlin.math.abs

/**
 * Treats Mihon's chapter number as a contradiction detector, never as canonical identity.
 *
 * A finite non-negative hint may corroborate an explicit regular numeric label, but it may
 * never override that label. This includes zero and fractional hints: physical providers
 * have exposed rows such as "Chapter 1" + 0 and "Chapter 38" + 0.1, which must fail closed
 * instead of being attached to an unrelated canonical chapter. Negative/invalid hints remain
 * non-authoritative placeholders.
 *
 * The hint never becomes canonical identity; both values remain source metadata until reconciliation.
 */
fun hasConflictingIntegerChapterHint(
    parsed: ParsedChapterLabel,
    rawNumberHint: Double?,
): Boolean {
    val hint = rawNumberHint ?: return false
    if (!hint.isFinite() || hint < 0.0 || hint > Int.MAX_VALUE.toDouble()) {
        return false
    }

    val identity = parsed.identity
    if (
        identity.type != CanonicalChapterType.REGULAR ||
        identity.alphaSuffix != null
    ) {
        return false
    }

    // displayNumber is produced from the parsed regular identity. Numeric forms such
    // as 1, 0.5 and 205.6 are safe to compare; semantic/part labels are not.
    val parsedNumber = parsed.displayNumber.toDoubleOrNull() ?: return false
    return abs(hint - parsedNumber) > NUMERIC_EPSILON
}

private const val NUMERIC_EPSILON = 1e-9
