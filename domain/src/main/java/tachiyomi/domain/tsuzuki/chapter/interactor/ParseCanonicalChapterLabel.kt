package tachiyomi.domain.tsuzuki.chapter.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterIdentity
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ParsedChapterLabel
import java.text.Normalizer
import java.util.Locale

/**
 * Parses source labels into conservative Tsuzuki chapter semantics.
 *
 * This parser intentionally recognizes only an explicit chapter marker,
 * a number at the start of a label, or a known semantic type. It does not
 * search arbitrary title text for numbers and does not use Mihon's
 * ChapterRecognition implementation.
 */
@Inject
class ParseCanonicalChapterLabel {

    operator fun invoke(rawLabel: String?, numericHint: Number? = null): ParsedChapterLabel =
        execute(rawLabel, numericHint)

    fun execute(rawLabel: String?, numericHint: Number? = null): ParsedChapterLabel {
        val original = rawLabel.orEmpty()
        val trimmed = original.trim()
        val hint = numericHint?.let(::formatNumericHint)
        val normalized = normalize(trimmed)

        if (normalized.isEmpty()) {
            return unknown(original, hint)
        }

        parseSemanticType(normalized)?.let { semantic ->
            return semantic.toParsed(original, hint)
        }

        val hasExplicitChapterPrefix = CHAPTER_PREFIX.find(normalized) != null
        val chapterText = removeChapterPrefix(normalized)
        parsePlaceholderSemantic(chapterText)?.let { semantic ->
            return semantic.toParsed(original, hint)
        }
        parsePartForm(chapterText)?.let { parsed ->
            return parsed.toParsed(original, hint)
        }
        parseNumberForm(chapterText, hasExplicitChapterPrefix)?.let { parsed ->
            return parsed.toParsed(original, hint)
        }

        return unknown(original, hint)
    }

    /** Alias for callers that use the source model's `numberHint` wording. */
    fun parse(rawLabel: String?, numberHint: Number? = null): ParsedChapterLabel =
        execute(rawLabel, numberHint)

    /** Alias retaining the raw-number wording used by inventory adapters. */
    fun executeWithRawNumberHint(rawLabel: String?, rawNumberHint: Number? = null): ParsedChapterLabel =
        execute(rawLabel, rawNumberHint)

    private fun parseSemanticType(normalized: String): SemanticParse? {
        val extra = SPECIAL_PREFIX.find(normalized)
        if (extra != null) {
            val remainder = normalized.substring(extra.range.last + 1).trim()
            if (!isAllowedRemainder(remainder)) return null
            return SemanticParse(
                type = if (extra.groupValues[1] == "extra") {
                    CanonicalChapterType.EXTRA
                } else {
                    CanonicalChapterType.SPECIAL
                },
                baseNumber = extra.groupValues[2].toIntOrNull(),
                part = extra.groupValues[3].toIntOrNull(),
                alphaSuffix = null,
                displayNumber = normalized
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .replaceFirstChar { it.uppercase(Locale.ROOT) },
                confidence = 1.0,
            )
        }

        val numberedSemantic = NUMBERED_SEMANTIC_PREFIX.find(normalized)
        if (numberedSemantic != null) {
            val remainder = normalized.substring(numberedSemantic.range.last + 1).trim()
            if (!isAllowedRemainder(remainder)) return null
            val type = when (numberedSemantic.groupValues[1]) {
                "prologue", "prologo" -> CanonicalChapterType.PROLOGUE
                else -> CanonicalChapterType.EPILOGUE
            }
            val number = numberedSemantic.groupValues[2].toIntOrNull() ?: return null
            val label = if (type == CanonicalChapterType.PROLOGUE) "Prologue" else "Epilogue"
            return SemanticParse(
                type = type,
                baseNumber = number,
                part = null,
                alphaSuffix = null,
                displayNumber = "$label $number",
                confidence = 1.0,
            )
        }

        val numberedOneShot = NUMBERED_ONE_SHOT_PREFIX.find(normalized)
        if (numberedOneShot != null) {
            val remainder = normalized.substring(numberedOneShot.range.last + 1).trim()
            if (!isAllowedRemainder(remainder)) return null
            val number = numberedOneShot.groupValues[1].toIntOrNull() ?: return null
            return SemanticParse(
                type = CanonicalChapterType.ONESHOT,
                baseNumber = number,
                part = null,
                alphaSuffix = null,
                displayNumber = "One-shot $number",
                confidence = 1.0,
            )
        }

        val oneShot = ONE_SHOT_PREFIX.find(normalized)
        if (oneShot != null) {
            val remainder = normalized.substring(oneShot.range.last + 1).trim()
            if (!isAllowedRemainder(remainder)) return null
            return SemanticParse(
                type = CanonicalChapterType.ONESHOT,
                baseNumber = null,
                part = null,
                alphaSuffix = null,
                displayNumber = "One-shot",
                confidence = 1.0,
            )
        }

        return when {
            startsWithWord(normalized, "prologue") || startsWithWord(normalized, "prologo") ->
                SemanticParse(
                    type = CanonicalChapterType.PROLOGUE,
                    baseNumber = null,
                    part = null,
                    alphaSuffix = null,
                    displayNumber = "Prologue",
                    confidence = 1.0,
                )

            startsWithWord(normalized, "epilogue") || startsWithWord(normalized, "epilogo") ->
                SemanticParse(
                    type = CanonicalChapterType.EPILOGUE,
                    baseNumber = null,
                    part = null,
                    alphaSuffix = null,
                    displayNumber = "Epilogue",
                    confidence = 1.0,
                )

            else -> null
        }
    }

    private fun removeChapterPrefix(normalized: String): String {
        return normalized.replaceFirst(CHAPTER_PREFIX, "").trimStart()
    }

    /**
     * Some sources use chapter number 0 as a placeholder for semantic entries,
     * for example "Ch. 0 - Oneshot". The semantic label is the stronger
     * identity signal and must not collapse into an unrelated regular chapter 0.
     */
    private fun parsePlaceholderSemantic(normalized: String): SemanticParse? {
        val match = ZERO_PLACEHOLDER_SEMANTIC.find(normalized) ?: return null
        return parseSemanticType(match.groupValues[1])
    }

    private fun parsePartForm(normalized: String): NumericParse? {
        val match = PART_FORM.find(normalized) ?: return null
        val remainder = normalized.substring(match.range.last + 1).trim()
        if (!isAllowedRemainder(remainder)) return null
        return NumericParse(
            baseNumber = match.groupValues[1].toIntOrNull() ?: return null,
            part = match.groupValues[2].toIntOrNull() ?: return null,
            alphaSuffix = null,
            displayNumber = "${match.groupValues[1].toInt()} Part ${match.groupValues[2].toInt()}",
            confidence = 1.0,
        )
    }

    private fun parseNumberForm(normalized: String, hasExplicitChapterPrefix: Boolean): NumericParse? {
        val match = NUMBER_FORM.find(normalized) ?: return null
        val remainder = normalized.substring(match.range.last + 1).trim()
        if (!isAllowedRemainder(remainder)) return null

        val baseNumber = match.groupValues[1].toIntOrNull() ?: return null
        val decimalPart = match.groupValues[2].takeIf(String::isNotEmpty)
        val suffix = match.groupValues[3].takeIf(String::isNotEmpty)
        val part = decimalPart?.toIntOrNull()
        val display = buildString {
            append(baseNumber)
            when {
                decimalPart != null -> append('.').append(decimalPart.toInt())
                suffix != null -> append(suffix)
            }
        }
        return NumericParse(
            baseNumber = baseNumber,
            part = part,
            alphaSuffix = suffix,
            displayNumber = display,
            confidence = if (hasExplicitChapterPrefix) 1.0 else 0.95,
        )
    }

    private fun isAllowedRemainder(remainder: String): Boolean {
        if (remainder.isEmpty()) return true
        // A chapter title may follow a parsed number, but another bare number
        // or a malformed second part is ambiguous and must not be discarded.
        if (remainder.matches(AMBIGUOUS_PART_REMAINDER)) return false
        return remainder.matches(TRAILING_TITLE)
    }

    private fun unknown(rawLabel: String, numericHint: String?): ParsedChapterLabel {
        val identity = CanonicalChapterIdentity(type = CanonicalChapterType.UNKNOWN)
        val normalizedRaw = normalize(rawLabel)
        val sortKey = buildString {
            append(identity.sortKey)
            append('|')
            append(normalizedRaw)
        }
        return ParsedChapterLabel(
            rawLabel = rawLabel,
            displayNumber = rawLabel.trim(),
            type = CanonicalChapterType.UNKNOWN,
            baseNumber = null,
            part = null,
            alphaSuffix = null,
            confidence = 0.0,
            identity = identity,
            sortKey = sortKey,
            numericHint = numericHint,
        )
    }

    private data class SemanticParse(
        val type: CanonicalChapterType,
        val baseNumber: Int?,
        val part: Int?,
        val alphaSuffix: String?,
        val displayNumber: String,
        val confidence: Double,
    ) {
        fun toParsed(rawLabel: String, numericHint: String?): ParsedChapterLabel {
            val identity = CanonicalChapterIdentity(type, baseNumber, part, alphaSuffix)
            return ParsedChapterLabel(
                rawLabel = rawLabel,
                displayNumber = displayNumber,
                type = type,
                baseNumber = baseNumber,
                part = part,
                alphaSuffix = alphaSuffix,
                confidence = confidence,
                identity = identity,
                sortKey = identity.sortKey,
                numericHint = numericHint,
            )
        }
    }

    private data class NumericParse(
        val baseNumber: Int,
        val part: Int?,
        val alphaSuffix: String?,
        val displayNumber: String,
        val confidence: Double,
    ) {
        fun toParsed(rawLabel: String, numericHint: String?): ParsedChapterLabel {
            val identity = CanonicalChapterIdentity(
                type = CanonicalChapterType.REGULAR,
                baseNumber = baseNumber,
                part = part,
                alphaSuffix = alphaSuffix,
            )
            return ParsedChapterLabel(
                rawLabel = rawLabel,
                displayNumber = displayNumber,
                type = CanonicalChapterType.REGULAR,
                baseNumber = baseNumber,
                part = part,
                alphaSuffix = alphaSuffix,
                confidence = confidence,
                identity = identity,
                sortKey = identity.sortKey,
                numericHint = numericHint,
            )
        }
    }

    private companion object {
        // Accent-free forms are used because input is normalized before matching.
        val CHAPTER_PREFIX = Regex("^(?:ch(?:apter)?|capitulo)\\s*\\.?\\s*")
        val ZERO_PLACEHOLDER_SEMANTIC = Regex("^0(?:[.]0+)?\\s*[-:]\\s*(.+)$")
        val SPECIAL_PREFIX =
            Regex("^(extra|special|especial)(?:\\s+|\\s*[:.-]\\s*)(\\d+)?(?:\\s+(?:part|pt)\\s+(\\d+))?")
        val NUMBERED_SEMANTIC_PREFIX =
            Regex("^(prologue|prologo|epilogue|epilogo)(?:\\s+|\\s*[:.-]\\s*)(\\d+)")
        val NUMBERED_ONE_SHOT_PREFIX = Regex("^one\\s*-?\\s*shot(?:\\s+|\\s*[:.-]\\s*)(\\d+)")
        val ONE_SHOT_PREFIX = Regex("^one\\s*-?\\s*shot")
        val PART_FORM = Regex("^(\\d+)\\s+(?:part|pt)\\s+(\\d+)")
        val NUMBER_FORM = Regex("^(\\d+)(?:[.]((?:\\d+))|[.]?([a-z]))?(?=$|\\s|[-:–—])")
        val AMBIGUOUS_PART_REMAINDER = Regex("^(?:part|pt)\\s+\\d+.*")
        val TRAILING_TITLE = Regex("^(?:[-:–—:]\\s*[^0-9].*|[a-z].*)$")

        fun normalize(value: String): String {
            return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replace("\\p{M}+".toRegex(), "")
                .lowercase(Locale.ROOT)
                .replace('–', '-')
                .replace('—', '-')
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        fun startsWithWord(value: String, word: String): Boolean {
            return value == word || value.startsWith("$word ") || value.startsWith("$word:") ||
                value.startsWith("$word-")
        }

        fun formatNumericHint(number: Number): String? {
            val text = number.toString().trim()
            if (text.isEmpty()) return null
            if (!text.contains('.')) return text
            return text.trimEnd('0').trimEnd('.').ifEmpty { "0" }
        }
    }
}
