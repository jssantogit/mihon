package tachiyomi.domain.tsuzuki.library.model

data class SourceLibraryRepresentation(
    val mihonMangaId: Long,
    val sourceId: Long,
    val sourceUrl: String,
    val language: String,
    val sourceAvailable: Boolean,
    val displayTitle: String,
    val dateAdded: Long,
    val hasStarted: Boolean,
)
