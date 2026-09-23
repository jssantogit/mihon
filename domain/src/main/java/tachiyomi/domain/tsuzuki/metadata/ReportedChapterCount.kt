package tachiyomi.domain.tsuzuki.metadata

data class ReportedChapterCount(
    val canonicalTitleId: String,
    val provider: String,
    val chapterCount: Int?,
    val updatedAt: Long,
)
