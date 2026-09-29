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
        remote_id = id
        title = this@toTrackSearch.title
        summary = synopsis
        total_chapters = numChapters
        total_volumes = numVolumes
        score = mean
        score_votes = numScoringUsers
        cover_url = covers?.large.orEmpty()
        tracking_url = "https://myanimelist.net/manga/$remote_id"
        publishing_status = status.replace("_", " ")
        publishing_type = mediaType.replace("_", " ")
        start_date = startDate ?: ""
        end_date = endDate ?: ""
        artists = authors
            .filter { authorNode -> authorNode.role.contains("Art") }
            .mapNotNull { authorNode -> authorNode.node.getFullName() }
        this.authors = this@toTrackSearch.authors
            .filter { authorNode -> authorNode.role.contains("Story") }
            .mapNotNull { authorNode -> authorNode.node.getFullName() }
        this.genres = this@toTrackSearch.genres.map { it.name }
    }
}

@Serializable
data class MALGenre(
    val id: Int,
    val name: String,
)
