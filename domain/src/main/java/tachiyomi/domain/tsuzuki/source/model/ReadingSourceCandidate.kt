package tachiyomi.domain.tsuzuki.source.model

data class ReadingSourceCandidate(
    val sourceId: Long,
    val sourceName: String,
    val language: String,
    val sourceUrl: String,
    val title: String,
    val thumbnailUrl: String?,
    val author: String?,
    val artist: String?,
    val description: String?,
    val genres: List<String>?,
    val status: Long,
)
