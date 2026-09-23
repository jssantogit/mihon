package tachiyomi.data.tsuzuki.kitsu.dto

import kotlinx.serialization.Serializable

@Serializable
data class KitsuMangaAttributes(
    val canonicalTitle: String? = null,
    val titles: KitsuTitles? = null,
    val synopsis: String? = null,
    val description: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val averageRating: String? = null,
    val userCount: Int? = null,
    val favoritesCount: Int? = null,
    val status: String? = null,
    val subtype: String? = null,
    val posterImage: KitsuImage? = null,
    val coverImage: KitsuImage? = null,
    val chapterCount: Int? = null,
    val volumeCount: Int? = null,
    val serialization: String? = null,
)
