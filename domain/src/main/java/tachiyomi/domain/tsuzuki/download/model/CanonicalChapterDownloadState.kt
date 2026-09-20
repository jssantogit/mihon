package tachiyomi.domain.tsuzuki.download.model

import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant

/**
 * Local view of physical downloads for one canonical chapter.
 *
 * Downloaded variants remain source-specific operational representations.
 * This model is derived from Mihon's download storage and is not sync state.
 */
data class CanonicalChapterDownloadState(
    val canonicalChapterId: String,
    val downloadedVariants: List<ChapterVariant>,
) {
    val hasDownload: Boolean
        get() = downloadedVariants.isNotEmpty()
}
