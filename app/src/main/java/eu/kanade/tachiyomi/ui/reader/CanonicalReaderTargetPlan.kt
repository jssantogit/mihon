package eu.kanade.tachiyomi.ui.reader

import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent

enum class CanonicalLocalReaderFormat {
    ARCHIVE,
    DIRECTORY,
    EPUB,
}

sealed interface CanonicalReaderTargetPlan {
    val canonicalChapterId: String

    data class Mihon(
        override val canonicalChapterId: String,
        val mangaId: Long,
        val chapterId: Long,
        val sourceId: Long,
    ) : CanonicalReaderTargetPlan

    data class Local(
        override val canonicalChapterId: String,
        val uri: String,
        val format: CanonicalLocalReaderFormat,
    ) : CanonicalReaderTargetPlan
}

fun planCanonicalReaderTarget(
    canonicalChapterId: String,
    target: PreparedChapterContent,
): CanonicalReaderTargetPlan {
    return when (target) {
        is PreparedChapterContent.MihonOperational -> CanonicalReaderTargetPlan.Mihon(
            canonicalChapterId = canonicalChapterId,
            mangaId = target.mangaId,
            chapterId = target.chapterId,
            sourceId = target.sourceId,
        )
        is PreparedChapterContent.LocalArchive -> CanonicalReaderTargetPlan.Local(
            canonicalChapterId = canonicalChapterId,
            uri = target.uri,
            format = CanonicalLocalReaderFormat.ARCHIVE,
        )
        is PreparedChapterContent.LocalDirectory -> CanonicalReaderTargetPlan.Local(
            canonicalChapterId = canonicalChapterId,
            uri = target.uri,
            format = CanonicalLocalReaderFormat.DIRECTORY,
        )
        is PreparedChapterContent.CanonicalDownload -> CanonicalReaderTargetPlan.Local(
            canonicalChapterId = canonicalChapterId,
            uri = target.uri,
            format = when (target.format.uppercase()) {
                "EPUB" -> CanonicalLocalReaderFormat.EPUB
                "DIRECTORY" -> CanonicalLocalReaderFormat.DIRECTORY
                "CBZ", "CBR", "ZIP", "RAR", "ARCHIVE" -> CanonicalLocalReaderFormat.ARCHIVE
                else -> error("Unsupported canonical local reader format: " + target.format)
            },
        )
    }
}
