package tachiyomi.domain.tsuzuki.reader.model

/** Mihon operational coordinates prepared for one canonical reading session. */
data class OperationalReaderChapter(
    val canonicalChapterId: String,
    val variantId: String,
    val sourceMappingId: String,
    val mihonMangaId: Long,
    val mihonChapterId: Long,
    val sourceId: Long,
)
