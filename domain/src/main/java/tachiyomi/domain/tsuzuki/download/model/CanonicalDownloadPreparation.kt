package tachiyomi.domain.tsuzuki.download.model

import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.content.ContentOption

/** Canonical download command outcome; storage ownership stays on CanonicalChapter. */
sealed interface CanonicalDownloadPreparation {

    data class Complete(
        val canonicalChapterId: String,
        val artifact: CanonicalDownloadArtifact,
        val reused: Boolean,
    ) : CanonicalDownloadPreparation

    data class SelectionRequired(
        val canonicalTitleId: String,
        val canonicalChapterId: String,
        val options: List<ContentOption>,
        val preferredAddonId: AddonId?,
    ) : CanonicalDownloadPreparation

    data class Unavailable(
        val canonicalChapterId: String,
    ) : CanonicalDownloadPreparation

    data class Failed(
        val canonicalChapterId: String,
        val error: Throwable,
    ) : CanonicalDownloadPreparation
}
