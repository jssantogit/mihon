package tachiyomi.domain.tsuzuki.source.model

data class MaterializedReadingSource(
    val sourceId: Long,
    val sourceUrl: String,
    val mihonMangaId: Long,
    val title: String,
)
