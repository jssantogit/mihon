package tachiyomi.domain.tsuzuki.chapter.interactor

import dev.zacsweers.metro.Inject
import java.text.Normalizer
import java.util.Locale

/** Extracts volume evidence only from an explicit leading volume and chapter label. */
@Inject
class ParseCanonicalChapterVolume {

    operator fun invoke(rawLabel: String?): Int? = execute(rawLabel)

    fun execute(rawLabel: String?): Int? {
        val normalized = normalize(rawLabel.orEmpty())
        val match = LEADING_VOLUME_CHAPTER_PREFIX.find(normalized) ?: return null
        if (VOLUME_MARKERS.findAll(normalized).take(2).count() != 1) return null
        return match.groupValues[1].toIntOrNull()
    }

    /** True when a label starts with a volume marker, even if its value is unusable. */
    fun hasExplicitVolumePrefix(rawLabel: String?): Boolean =
        VOLUME_PREFIX_START.containsMatchIn(normalize(rawLabel.orEmpty()))

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .replace('–', '-')
            .replace('—', '-')
            .replace(WHITESPACE, " ")
            .trim()

    private companion object {
        val COMBINING_MARKS = Regex("\\p{M}+")
        val WHITESPACE = Regex("\\s+")
        val VOLUME_PREFIX_START = Regex("^vol(?:ume)?(?:\\b|\\.)")
        val VOLUME_MARKERS = Regex("\\bvol(?:ume)?\\.?\\s*(?:\\d+|none)\\b")
        val LEADING_VOLUME_CHAPTER_PREFIX = Regex(
            "^vol(?:ume)?\\.?\\s*(\\d+)\\s*(?:[-:|/]\\s*|\\s+)" +
                "(?=(?:ch(?:apter)?|capitulo)\\s*\\.?\\s*\\d)",
        )
    }
}
