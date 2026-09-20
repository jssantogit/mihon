package tachiyomi.domain.tsuzuki.model

data class SourceRepresentation(
    val id: String,
    val canonicalTitleId: String,
    val mihonMangaId: Long?,
    val sourceId: Long,
    val sourceUrl: String,
    val language: String,
    val matchConfidence: Double?,
    val verifiedByUser: Boolean,
    val availability: SourceMappingAvailability,
    val preferredOverride: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
) {
    data class Key(
        val sourceId: Long,
        val sourceUrl: String,
    )

    val key: Key
        get() = Key(sourceId = sourceId, sourceUrl = sourceUrl)

    val localMihonMangaId: Long?
        get() = mihonMangaId
}
