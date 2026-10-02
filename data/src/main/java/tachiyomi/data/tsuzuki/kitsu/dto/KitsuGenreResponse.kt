package tachiyomi.data.tsuzuki.kitsu.dto

import kotlinx.serialization.Serializable

@Serializable
data class KitsuGenreResponse(
    val data: List<KitsuGenreResource> = emptyList(),
)

@Serializable
data class KitsuGenreResource(
    val id: String,
    val attributes: KitsuGenreAttributes,
)

@Serializable
data class KitsuGenreAttributes(
    val name: String,
    val slug: String? = null,
)
