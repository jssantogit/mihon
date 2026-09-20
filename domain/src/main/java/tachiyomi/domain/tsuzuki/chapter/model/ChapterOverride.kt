package tachiyomi.domain.tsuzuki.chapter.model

enum class ChapterOverrideKind {
    CHAPTER_TYPE,
    SOURCE_CHAPTER_MAPPING,
    PREFERRED_VARIANT,
    MARK_MISSING,
    MERGE_PARTS,
    CUSTOM,
}

/**
 * Durable user-owned chapter repair/resolution state.
 *
 * Cross-device identity intentionally avoids local SQL row IDs such as
 * SourceTitleMapping.id and ChapterVariant.id. [canonicalChapterKey] is a
 * portable structured chapter key (for example CanonicalChapterIdentity.sortKey)
 * when an override targets a canonical chapter. Source-targeted overrides use
 * [sourceId], [sourceTitleUrl], and [sourceChapterId].
 */
data class ChapterOverride(
    val id: String,
    val canonicalTitleId: String,
    val canonicalChapterKey: String? = null,
    val sourceId: Long? = null,
    val sourceTitleUrl: String? = null,
    val sourceChapterId: String? = null,
    val kind: ChapterOverrideKind,
    val payloadJson: String = "{}",
    val schemaVersion: Int = CURRENT_CHAPTER_OVERRIDE_SCHEMA_VERSION,
    val revision: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
) {
    init {
        require(id.isNotBlank()) { "Chapter override id cannot be blank" }
        require(canonicalTitleId.isNotBlank()) { "Chapter override canonicalTitleId cannot be blank" }
        require(canonicalChapterKey == null || canonicalChapterKey.isNotBlank()) {
            "Chapter override canonicalChapterKey cannot be blank"
        }
        require(sourceTitleUrl == null || sourceTitleUrl.isNotBlank()) {
            "Chapter override sourceTitleUrl cannot be blank"
        }
        require(sourceChapterId == null || sourceChapterId.isNotBlank()) {
            "Chapter override sourceChapterId cannot be blank"
        }
        require((sourceId == null) == (sourceTitleUrl == null)) {
            "Chapter override sourceId and sourceTitleUrl must be supplied together"
        }
        require(sourceChapterId == null || sourceId != null) {
            "Chapter override sourceChapterId requires a source identity"
        }
        require(payloadJson.isNotBlank()) { "Chapter override payloadJson cannot be blank" }
        require(schemaVersion > 0) { "Chapter override schemaVersion must be positive" }
        require(revision >= 0) { "Chapter override revision cannot be negative" }
        require(createdAt >= 0) { "Chapter override createdAt cannot be negative" }
        require(updatedAt >= 0) { "Chapter override updatedAt cannot be negative" }
        require(deletedAt == null || deletedAt >= 0) { "Chapter override deletedAt cannot be negative" }
    }

    val isDeleted: Boolean
        get() = deletedAt != null
}

const val CURRENT_CHAPTER_OVERRIDE_SCHEMA_VERSION: Int = 1
