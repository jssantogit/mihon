package tachiyomi.domain.tsuzuki.reader.model

/** User reading progress owned by a canonical chapter, never by a source variant. */
data class CanonicalChapterProgress(
    val canonicalChapterId: String,
    val read: Boolean = false,
    val lastPageRead: Long = 0L,
    val updatedAt: Long = 0L,
)
