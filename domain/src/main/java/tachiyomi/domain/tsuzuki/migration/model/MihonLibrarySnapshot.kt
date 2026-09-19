package tachiyomi.domain.tsuzuki.migration.model

data class MihonLibrarySnapshot(
    val mihonMangaId: Long,
    val sourceId: Long,
    val sourceUrl: String,
    val sourceLanguage: String,
    val sourceAvailable: Boolean,
    val title: String,
    val dateAdded: Long,
    val hasStarted: Boolean,
)
