package tachiyomi.domain.tsuzuki.reader.model

/**
 * User reading progress owned by a canonical chapter.
 *
 * Page position is only meaningful together with [lastVariantId]; switching
 * source variants must not blindly reuse another release's page index.
 */
data class CanonicalChapterProgress(
    val canonicalChapterId: String,
    val read: Boolean = false,
    val lastPageRead: Long = 0L,
    val lastVariantId: String? = null,
    val updatedAt: Long = 0L,
)
