package tachiyomi.data.tsuzuki.kitsu.dto

import kotlinx.serialization.Serializable

@Serializable
data class KitsuMappingResource(
    val id: String,
    val type: String,
    val attributes: KitsuMappingAttributes = KitsuMappingAttributes(),
    val relationships: KitsuMappingRelationships = KitsuMappingRelationships(),
)

@Serializable
data class KitsuMappingAttributes(
    val externalSite: String = "",
    val externalId: String = "",
)

@Serializable
data class KitsuMappingRelationships(
    val item: KitsuMappingItemRelationship = KitsuMappingItemRelationship(),
    val media: KitsuMappingItemRelationship = KitsuMappingItemRelationship(),
)

@Serializable
data class KitsuMappingItemRelationship(
    val data: KitsuResourceIdentifier? = null,
)

@Serializable
data class KitsuResourceIdentifier(
    val type: String,
    val id: String,
)
