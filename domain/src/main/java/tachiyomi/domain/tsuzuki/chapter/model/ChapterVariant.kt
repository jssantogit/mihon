package tachiyomi.domain.tsuzuki.chapter.model

import kotlinx.serialization.json.JsonObject

/** One source release mapped to a [CanonicalChapter]. */
data class ChapterVariant(
    val id: String,
    val canonicalChapterId: String,
    val sourceMappingId: String = "",
    val sourceId: Long = 0L,
    val mihonMangaId: Long? = null,
    val mihonChapterId: Long? = null,
    val sourceChapterId: String = "",
    val sourceChapterUrl: String? = null,
    val language: String = "",
    val scanlationGroup: String? = null,
    val version: Long? = null,
    val releaseDate: Long? = null,
    val rawName: String = "",
    /** Source number hint only; it is not a canonical identity component. */
    val rawNumberHint: Double? = null,
    val rawSourceOrder: Long? = null,
    /** Lossless structured metadata from the source boundary (for example SChapter.memo). */
    val rawSourceMetadata: JsonObject = JsonObject(emptyMap()),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {

    val sourceUrl: String?
        get() = sourceChapterUrl

    val rawSourceLabel: String
        get() = rawName
}
