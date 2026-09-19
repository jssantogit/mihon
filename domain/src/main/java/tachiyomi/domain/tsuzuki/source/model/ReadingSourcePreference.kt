package tachiyomi.domain.tsuzuki.source.model

data class ReadingSourcePreference(
    val language: String,
    val sourceId: Long,
    val position: Int,
)
