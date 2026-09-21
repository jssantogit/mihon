package tachiyomi.domain.tsuzuki.content

import tachiyomi.domain.tsuzuki.addon.AddonId

sealed interface ContentDelivery {
    data class Mihon(
        val sourceId: Long,
        val mangaId: Long,
        val chapterId: Long,
    ) : ContentDelivery

    data class LocalArchive(
        val uri: String,
    ) : ContentDelivery

    data class LocalDirectory(
        val uri: String,
    ) : ContentDelivery

    data class Torrent(
        val infoHash: String,
        val magnetUri: String?,
        val fileIndex: Int?,
        val filePath: String?,
    ) : ContentDelivery
}

data class ContentOption(
    val key: String,
    val canonicalChapterId: String,
    val addonId: AddonId,
    val language: String?,
    val scanlationGroup: String?,
    val releaseDate: Long?,
    val delivery: ContentDelivery,
)
