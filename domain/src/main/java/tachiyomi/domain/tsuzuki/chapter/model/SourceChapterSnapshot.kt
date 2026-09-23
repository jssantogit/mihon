package tachiyomi.domain.tsuzuki.chapter.model

import kotlinx.serialization.json.JsonObject

/**
 * A source-provided chapter observation.
 *
 * This is deliberately not Mihon's [tachiyomi.domain.chapter.model.Chapter]. It
 * carries provider evidence across the adapter boundary without granting that
 * evidence canonical identity.
 */
data class SourceChapterSnapshot(
    val sourceId: Long,
    val sourceMappingId: String,
    /** Stable source identity. The adapter uses the source URL for this field. */
    val sourceChapterId: String,
    val sourceChapterUrl: String = sourceChapterId,
    val rawName: String,
    val language: String = "",
    val scanlationGroup: String? = null,
    val releaseDate: Long? = null,
    /** Provider hint only; it never participates in canonical identity. */
    val rawNumberHint: Double? = null,
    val rawSourceOrder: Long? = null,
    val version: Long? = null,
    val mihonMangaId: Long? = null,
    val mihonChapterId: Long? = null,
    val rawSourceMetadata: JsonObject = JsonObject(emptyMap()),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    val mappingId: String
        get() = sourceMappingId

    val sourceUrl: String
        get() = sourceChapterUrl

    val rawSourceLabel: String
        get() = rawName

    val numberHint: Double?
        get() = rawNumberHint
}
