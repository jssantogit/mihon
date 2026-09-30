package tachiyomi.data.tsuzuki.kitsu.dto

import kotlinx.serialization.Serializable

@Serializable
data class KitsuMappingResponse(
    val data: List<KitsuMappingResource> = emptyList(),
    val included: List<KitsuMangaResource> = emptyList(),
)
