package eu.kanade.tachiyomi.data.track.mangaupdates.dto

import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.util.lang.htmlDecode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MURecord(
    @SerialName("series_id")
    val seriesId: Long? = null,
    val title: String? = null,
    val associated: List<MUAssociatedTitle> = emptyList(),
    val url: String? = null,
    val description: String? = null,
    val image: MUImage? = null,
    val type: String? = null,
    val year: String? = null,
    @SerialName("bayesian_rating")
    val bayesianRating: Double? = null,
    @SerialName("rating_votes")
    val ratingVotes: Int? = null,
    @SerialName("latest_chapter")
    val latestChapter: Int? = null,
    val genres: List<MUGenre> = emptyList(),
    val categories: List<MUCategory> = emptyList(),
    val status: String? = null,
    val completed: Boolean? = null,
    val authors: List<MUAuthor> = emptyList(),
) {
    fun toTrackSearch(id: Long): TrackSearch {
        return TrackSearch.create(id).apply {
            remote_id = this@MURecord.seriesId ?: 0L
            title = this@MURecord.title?.decodeHtmlIfNeeded() ?: ""
            alternate_titles = this@MURecord.associated
                .map { it.title.decodeHtmlIfNeeded() }
                .filter(String::isNotBlank)
                .distinct()
            total_chapters = 0
            cover_url = this@MURecord.image?.url?.original ?: ""
            summary = this@MURecord.description?.decodeHtmlIfNeeded() ?: ""
            tracking_url = this@MURecord.url ?: ""
            publishing_status = when {
                this@MURecord.completed == true -> "Finished"
                else -> this@MURecord.status.orEmpty()
            }
            publishing_type = this@MURecord.type.orEmpty()
            start_date = this@MURecord.year.orEmpty()
            score = this@MURecord.bayesianRating?.takeIf { it > 0 } ?: -1.0
            score_votes = this@MURecord.ratingVotes
            authors = this@MURecord.authors.filter { it.type == "Author" }.map { it.name }
            artists = this@MURecord.authors.filter { it.type == "Artist" }.map { it.name }
            genres = this@MURecord.genres.map { it.genre }
            tags = this@MURecord.categories.map { it.category }
        }
    }
}

private fun String.decodeHtmlIfNeeded(): String {
    return if (contains('<') || contains('&')) htmlDecode() else this
}

@Serializable
data class MUAssociatedTitle(
    val title: String,
)

@Serializable
data class MUGenre(
    val genre: String,
)

@Serializable
data class MUCategory(
    val category: String,
    val votes: Int? = null,
)
