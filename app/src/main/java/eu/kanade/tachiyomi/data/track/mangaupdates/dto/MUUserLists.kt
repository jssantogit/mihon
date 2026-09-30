package eu.kanade.tachiyomi.data.track.mangaupdates.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MUUserList(
    @SerialName("list_id")
    val listId: Long,
    val title: String,
    val type: String,
    val custom: Boolean = false,
)

@Serializable
data class MUUserListSearchResponse(
    @SerialName("total_hits")
    val totalHits: Int = 0,
    val page: Int = 0,
    @SerialName("per_page")
    val perPage: Int = 0,
    val results: List<MUUserListSearchResult> = emptyList(),
)

@Serializable
data class MUUserListSearchResult(
    @SerialName("series_id")
    val seriesId: Long,
    @SerialName("series_title")
    val seriesTitle: String = "",
    val volume: Int? = null,
    val chapter: Int? = null,
    val metadata: MUUserListSearchMetadata = MUUserListSearchMetadata(),
)

@Serializable
data class MUUserListSearchMetadata(
    @SerialName("user_rating")
    val userRating: Double? = null,
    @SerialName("user_list")
    val userList: MUUserListMembership? = null,
)

@Serializable
data class MUUserListMembership(
    val series: MUSeries? = null,
    @SerialName("list_id")
    val listId: Long? = null,
    @SerialName("list_type")
    val listType: String? = null,
    val status: MUStatus? = null,
    @SerialName("time_added")
    val timeAdded: MUUserListTimestamp? = null,
)

@Serializable
data class MUUserListTimestamp(
    val timestamp: Long? = null,
    @SerialName("as_rfc3339")
    val asRfc3339: String? = null,
)
