package tachiyomi.domain.tsuzuki.reader.model

data class CanonicalTrackerProgress(
    val canonicalTitleId: String,
    val canonicalChapterId: String,
    val chapterNumber: Double,
)
