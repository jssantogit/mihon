package tachiyomi.data.tsuzuki.kitsu.dto

import kotlinx.serialization.Serializable

@Serializable
data class KitsuMappingsResponse(
    val data: List<KitsuMappingResource> = emptyList(),
    val included: List<KitsuMangaResource> = emptyList(),
)
