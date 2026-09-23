package tachiyomi.domain.tsuzuki.reader.model

/** Aggregated canonical reading history. */
data class CanonicalChapterHistory(
    val canonicalChapterId: String,
    val lastVariantId: String? = null,
    val lastReadAt: Long? = null,
    val totalReadDuration: Long = 0L,
)

data class CanonicalChapterHistoryUpdate(
    val canonicalChapterId: String,
    val variantId: String? = null,
    val readAt: Long,
    val sessionReadDuration: Long,
)
