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

/**
 * Rejects provisional source rows whose metadata is structurally unsafe even when the
 * parsed label and Mihon number happen to agree.
 *
 * Some providers expose deleted/tombstone rows as ordinary chapters. MangaDot-family
 * sources can also expose zero placeholders: titled synthetic rows (for example,
 * "Chapter 0: Tragedy") and bare volume-scoped rows such as "Vol. 3 Ch. 0".
 * The latter is emitted by some aggregate sources for volume-level content and must
 * not become one canonical chapter zero per volume. Neither shape is strong enough
 * to become canonical identity or a Reader option without independent evidence.
 * Plain unqualified numeric zero/fractional chapters remain valid and test-covered.
 */
fun isUnsafeProvisionalChapterEvidence(
    parsed: ParsedChapterLabel,
    rawLabel: String,
    rawNumberHint: Double?,
    volume: Int? = null,
): Boolean {
    if (hasConflictingIntegerChapterHint(parsed, rawNumberHint)) return true
    if (rawLabel.contains(DELETED_TOMBSTONE, ignoreCase = true)) return true

    val hint = rawNumberHint ?: return false
    if (!hint.isFinite() || abs(hint) > NUMERIC_EPSILON) return false
    val identity = parsed.identity
    if (
        identity.type != CanonicalChapterType.REGULAR ||
        identity.baseNumber != 0 ||
        identity.part != null ||
        identity.alphaSuffix != null
    ) {
        return false
    }
    val trimmedLabel = rawLabel.trim()
    if (TITLED_ZERO_PLACEHOLDER.matches(trimmedLabel)) return true
    return volume != null && BARE_VOLUME_ZERO_PLACEHOLDER.matches(trimmedLabel)
}

private const val DELETED_TOMBSTONE = "[DELETED]"
private val TITLED_ZERO_PLACEHOLDER = Regex(
    pattern = "^(?:chapter|ch(?:apter)?|capitulo)\\s*\\.?\\s*0(?:\\.0+)?\\s*:\\s*\\S.*$",
    option = RegexOption.IGNORE_CASE,
)
private val BARE_VOLUME_ZERO_PLACEHOLDER = Regex(
    pattern = "^vol(?:ume)?\\.?\\s*\\d+\\s*(?:(?:[-:|/]\\s*)|\\s+)" +
        "(?:ch(?:apter)?|cap[ií]tulo)\\s*\\.?\\s*0(?:\\.0+)?\\s*$",
    option = RegexOption.IGNORE_CASE,
)

private const val NUMERIC_EPSILON = 1e-9
