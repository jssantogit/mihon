package tachiyomi.data.tsuzuki.kitsu.dto

import kotlinx.serialization.Serializable

@Serializable
data class KitsuLinks(
    val first: String? = null,
    val next: String? = null,
    val prev: String? = null,
    val last: String? = null,
)
