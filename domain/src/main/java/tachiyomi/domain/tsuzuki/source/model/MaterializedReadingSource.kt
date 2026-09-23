package tachiyomi.domain.tsuzuki.source.model

data class MaterializedReadingSource(
    val mihonMangaId: Long,
    val sourceId: Long,
    val sourceUrl: String,
    val language: String,
    val providerTitleKey: String = "$sourceId:$sourceUrl",
    val runtimePayload: ByteArray = byteArrayOf(),
)
