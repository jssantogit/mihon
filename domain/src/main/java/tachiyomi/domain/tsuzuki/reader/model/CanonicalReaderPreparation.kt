package tachiyomi.domain.tsuzuki.reader.model

import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.content.ContentOption

sealed interface CanonicalReaderPreparation {

    data class Ready(
        val canonicalChapterId: String,
        val target: PreparedChapterContent,
        val usedFallback: Boolean,
    ) : CanonicalReaderPreparation

    data class SelectionRequired(
        val canonicalTitleId: String,
        val canonicalChapterId: String,
        val options: List<ContentOption>,
        val preferredAddonId: AddonId?,
        val preferredUnavailable: Boolean,
    ) : CanonicalReaderPreparation

    data class Unavailable(
        val canonicalChapterId: String,
    ) : CanonicalReaderPreparation

    data class Failed(
        val canonicalChapterId: String,
        val error: Throwable,
    ) : CanonicalReaderPreparation
}
