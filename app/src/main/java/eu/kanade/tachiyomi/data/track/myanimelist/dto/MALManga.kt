package eu.kanade.tachiyomi.data.track.myanimelist.dto

import eu.kanade.tachiyomi.data.track.model.TrackSearch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MALManga(
    val id: Long,
    val title: String,
    val synopsis: String = "",
    @SerialName("num_chapters")
    val numChapters: Long,
    @SerialName("num_volumes")
    val numVolumes: Long = 0,
    val mean: Double = -1.0,
    @SerialName("num_scoring_users")
    val numScoringUsers: Int? = null,
    @SerialName("main_picture")
    val covers: MALMangaCovers?,
    val status: String,
    @SerialName("media_type")
    val mediaType: String,
    @SerialName("start_date")
    val startDate: String?,
    @SerialName("end_date")
    val endDate: String? = null,
    val authors: List<MALAuthorNode> = emptyList(),
    val genres: List<MALGenre> = emptyList(),
)

@Serializable
data class MALAuthorNode(
    val node: MALAuthor,
    val role: String,
)

@Serializable
data class MALAuthor(
    val id: Int,
    @SerialName("first_name")
    val firstName: String,
    @SerialName("last_name")
    val lastName: String,
) {
    fun getFullName(): String? = "$firstName $lastName".trim().ifBlank { null }
}

@Serializable
data class MALMangaCovers(
    val large: String = "",
)

internal fun MALManga.toTrackSearch(trackerId: Long): TrackSearch {
    return TrackSearch.create(trackerId).apply {
        remote_id = this@toTrackSearch.id
        title = this@toTrackSearch.title
        summary = this@toTrackSearch.synopsis
        total_chapters = this@toTrackSearch.numChapters
        total_volumes = this@toTrackSearch.numVolumes
        score = this@toTrackSearch.mean
        score_votes = this@toTrackSearch.numScoringUsers
        cover_url = this@toTrackSearch.covers?.large.orEmpty()
        tracking_url = "https://myanimelist.net/manga/$remote_id"
        publishing_status = this@toTrackSearch.status.replace("_", " ")
        publishing_type = this@toTrackSearch.mediaType.replace("_", " ")
        start_date = this@toTrackSearch.startDate ?: ""
        end_date = this@toTrackSearch.endDate ?: ""
        artists = this@toTrackSearch.authors
            .filter { authorNode -> authorNode.role.contains("Art") }
            .mapNotNull { authorNode -> authorNode.node.getFullName() }
        authors = this@toTrackSearch.authors
            .filter { authorNode -> authorNode.role.contains("Story") }
            .mapNotNull { authorNode -> authorNode.node.getFullName() }
        genres = this@toTrackSearch.genres.map { it.name }
    }
}
@Serializable
data class MALGenre(
    val id: Int,
    val name: String,
)
