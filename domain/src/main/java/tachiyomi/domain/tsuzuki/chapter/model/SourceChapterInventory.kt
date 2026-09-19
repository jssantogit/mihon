package tachiyomi.domain.tsuzuki.chapter.model

/**
 * The complete chapter observation returned for one accepted source mapping.
 * An empty [chapters] list is a valid observation and does not imply deletion.
 */
data class SourceChapterInventory(
    val sourceMappingId: String,
    val sourceId: Long,
    val canonicalTitleId: String = "",
    val chapters: List<SourceChapterSnapshot>,
    val mihonMangaId: Long? = null,
    val language: String = "",
) {
    val mappingId: String
        get() = sourceMappingId

    val snapshots: List<SourceChapterSnapshot>
        get() = chapters
}
