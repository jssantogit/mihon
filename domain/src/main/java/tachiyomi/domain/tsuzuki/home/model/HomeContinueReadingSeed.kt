package tachiyomi.domain.tsuzuki.home.model

/**
 * Provider-neutral local seed for Continue Reading.
 *
 * This is derived from canonical chapter progress, not Library membership.
 */
data class HomeContinueReadingSeed(
    val canonicalTitleId: String,
    val title: String,
    val canonicalChapterId: String,
    val chapterDisplayNumber: String,
    val lastPageRead: Long,
    val read: Boolean,
    val updatedAt: Long,
    val lastVariantId: String?,
)
