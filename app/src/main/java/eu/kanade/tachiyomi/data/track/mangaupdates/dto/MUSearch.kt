package eu.kanade.tachiyomi.data.track.mangaupdates.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MUSearchResult(
    @SerialName("total_hits")
    val totalHits: Int = 0,
    val page: Int = 1,
    @SerialName("per_page")
    val perPage: Int = 0,
    val results: List<MUSearchResultItem> = emptyList(),
)

@Serializable
data class MUSearchResultItem(
    val record: MURecord,
)
