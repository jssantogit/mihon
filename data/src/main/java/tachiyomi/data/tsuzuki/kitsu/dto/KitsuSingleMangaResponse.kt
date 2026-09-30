package tachiyomi.data.tsuzuki.kitsu.dto

import kotlinx.serialization.Serializable

@Serializable
data class KitsuSingleMangaResponse(
    val data: KitsuMangaResource,
    val included: List<KitsuMappingResource> = emptyList(),
)
