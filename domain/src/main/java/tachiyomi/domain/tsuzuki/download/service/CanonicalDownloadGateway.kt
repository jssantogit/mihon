package tachiyomi.domain.tsuzuki.download.service

import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant

/**
 * Local operational boundary for Mihon's source-specific download storage.
 *
 * Implementations may use disposable Mihon IDs to locate files, but download
 * ownership remains attached to [ChapterVariant] and never becomes sync identity.
 * Source availability and physical download presence are deliberately separate.
 * Implementations must verify physical presence from local storage instead of
 * inferring it from extension availability or an in-memory source index.
 */
interface CanonicalDownloadGateway {
    suspend fun isDownloaded(variant: ChapterVariant): Boolean
}
