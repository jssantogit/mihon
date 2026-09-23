package tachiyomi.data.tsuzuki.kitsu.dto

import kotlinx.serialization.Serializable

@Serializable
data class KitsuMangaResource(
    val id: String,
    val type: String,
    val attributes: KitsuMangaAttributes = KitsuMangaAttributes(),
)
