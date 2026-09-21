package tachiyomi.domain.tsuzuki.reader.model

sealed interface PreparedChapterContent {
    data class MihonOperational(
        val mangaId: Long,
        val chapterId: Long,
        val sourceId: Long,
    ) : PreparedChapterContent

    data class LocalArchive(
        val uri: String,
    ) : PreparedChapterContent

    data class LocalDirectory(
        val uri: String,
    ) : PreparedChapterContent

    data class CanonicalDownload(
        val uri: String,
        val format: String,
    ) : PreparedChapterContent
}
