package tachiyomi.domain.tsuzuki.download.service

import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant

/**
 * Local operational boundary for Mihon's source-specific download storage.
 */
interface CanonicalDownloadGateway {
    suspend fun isDownloaded(variant: ChapterVariant): Boolean
}
