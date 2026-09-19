package tachiyomi.domain.tsuzuki.source.model

data class ReadingSourceDescriptor(
    val sourceId: Long,
    val name: String,
    val language: String,
    val isInstalled: Boolean = true,
    val isEnabled: Boolean = true,
)
