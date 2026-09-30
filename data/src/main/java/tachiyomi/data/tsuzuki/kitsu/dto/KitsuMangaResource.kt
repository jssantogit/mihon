package tachiyomi.data.tsuzuki.kitsu.dto

import kotlinx.serialization.Serializable

@Serializable
data class KitsuMangaResource(
    val id: String,
    val type: String,
    val attributes: KitsuMangaAttributes = KitsuMangaAttributes(),
    val relationships: KitsuMangaRelationships = KitsuMangaRelationships(),
)

@Serializable
data class KitsuMangaRelationships(
    val mappings: KitsuResourceIdentifiersRelationship = KitsuResourceIdentifiersRelationship(),
)

@Serializable
data class KitsuResourceIdentifiersRelationship(
    val data: List<KitsuResourceIdentifier> = emptyList(),
)
