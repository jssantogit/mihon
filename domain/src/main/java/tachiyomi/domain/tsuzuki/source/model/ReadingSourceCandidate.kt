package tachiyomi.domain.tsuzuki.source.model

data class ReadingSourceCandidate(
    val sourceId: Long,
    val sourceUrl: String,
    val title: String,
    val thumbnailUrl: String? = null,
)
