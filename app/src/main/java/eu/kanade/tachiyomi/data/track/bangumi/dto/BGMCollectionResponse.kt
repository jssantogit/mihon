package eu.kanade.tachiyomi.data.track.bangumi.dto

import eu.kanade.tachiyomi.data.track.bangumi.Bangumi
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
// Incomplete DTO with only our needed attributes
data class BGMCollectionResponse(
    @SerialName("subject_id")
    val subjectId: Long? = null,
    @SerialName("subject_type")
    val subjectType: Int? = null,
    val rate: Int?,
    val type: Int?,
    @SerialName("ep_status")
    val epStatus: Int? = 0,
    @SerialName("vol_status")
    val volStatus: Int? = 0,
    @SerialName("updated_at")
    val updatedAt: String? = null,
    val private: Boolean = false,
    val subject: BGMSlimSubject? = null,
) {
    fun getStatus(): Long = when (type) {
        1 -> Bangumi.PLAN_TO_READ
        2 -> Bangumi.COMPLETED
        3 -> Bangumi.READING
        4 -> Bangumi.ON_HOLD
        5 -> Bangumi.DROPPED
        else -> throw NotImplementedError("Unknown status: $type")
    }
}

@Serializable
// Incomplete DTO with only our needed attributes
data class BGMSlimSubject(
    val id: Long? = null,
    val type: Int? = null,
    val name: String = "",
    @SerialName("name_cn")
    val nameCn: String = "",
    @SerialName("short_summary")
    val shortSummary: String = "",
    val date: String? = null,
    val images: BGMSubjectImages? = null,
    val volumes: Int? = null,
    val eps: Int? = null,
    @SerialName("collection_total")
    val collectionTotal: Int? = null,
    val score: Double? = null,
    val tags: List<BGMSubjectTag> = emptyList(),
) {
    fun toTrackSearch(trackerId: Long, fallbackId: Long): TrackSearch =
        TrackSearch.create(trackerId).apply {
            remote_id = id ?: fallbackId
            title = nameCn.ifBlank { name }
            cover_url = images?.common.orEmpty()
            summary = shortSummary
            tracking_url = "https://bangumi.tv/subject/$remote_id"
            this.score = this@BGMSlimSubject.score ?: -1.0
            this.tags = this@BGMSlimSubject.tags.map(BGMSubjectTag::name)
            total_chapters = eps?.toLong() ?: 0L
            total_volumes = volumes?.toLong() ?: 0L
            publishing_type = ""
            start_date = date.orEmpty()
        }
}

@Serializable
data class BGMCollectionPage(
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
    val data: List<BGMCollectionResponse> = emptyList(),
)
