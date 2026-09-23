package tachiyomi.domain.tsuzuki.chapter.update.model

data class CanonicalChapterUpdateState(
    val canonicalChapterId: String,
    val canonicalTitleId: String,
    val firstSeenAt: Long,
    val acknowledgedAt: Long? = null,
) {
    init {
        require(canonicalChapterId.isNotBlank()) { "Canonical chapter ID must not be blank" }
        require(canonicalTitleId.isNotBlank()) { "Canonical title ID must not be blank" }
        require(firstSeenAt >= 0) { "Chapter first-seen time must not be negative" }
        require(acknowledgedAt == null || acknowledgedAt >= 0) {
            "Chapter acknowledgement time must not be negative"
        }
    }
}
