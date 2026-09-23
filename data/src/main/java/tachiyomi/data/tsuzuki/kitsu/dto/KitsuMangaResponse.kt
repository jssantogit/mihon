package tachiyomi.data.tsuzuki.kitsu.dto

import kotlinx.serialization.Serializable

@Serializable
data class KitsuMangaResponse(
    val data: List<KitsuMangaResource> = emptyList(),
    val meta: KitsuMeta? = null,
    val links: KitsuLinks? = null,
)
