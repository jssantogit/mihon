package eu.kanade.tachiyomi.data.track.mangaupdates

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.mangaupdates.dto.MUListItem
import eu.kanade.tachiyomi.data.track.mangaupdates.dto.MURating
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import tachiyomi.i18n.MR
import tachiyomi.domain.track.model.Track as DomainTrack

data class MangaUpdatesCollectionQuery(
    val search: String? = null,
    val licensed: Boolean? = null,
    val type: String? = null,
    val category: String? = null,
    val releaseFilter: String? = null,
    val genre: String? = null,
    val excludeGenre: String? = null,
    val orderBy: String,
    val page: Int,
    val perPage: Int,
) {
    init {
        require(page >= 1) { "MangaUpdates page must be one-based" }
        require(perPage > 0) { "MangaUpdates perPage must be positive" }
    }
}

data class MangaUpdatesCollectionPage(
    val items: List<TrackSearch>,
    val page: Int,
    val perPage: Int,
    val totalHits: Int,
)

interface MangaUpdatesIntegrationApi {
    suspend fun search(query: String): List<TrackSearch>

    suspend fun discover(
        orderBy: String,
        offset: Int,
        limit: Int,
    ): List<TrackSearch>

    suspend fun getMangaDetails(id: Long): TrackSearch

    suspend fun collectionSearch(
        query: MangaUpdatesCollectionQuery,
    ): MangaUpdatesCollectionPage = MangaUpdatesCollectionPage(
        items = emptyList(),
        page = query.page,
        perPage = query.perPage,
        totalHits = 0,
    )
}

class MangaUpdates(id: Long) : BaseTracker(id, "MangaUpdates"), DeletableTracker {

    companion object {
        const val READING_LIST = 0L
        const val WISH_LIST = 1L
        const val COMPLETE_LIST = 2L
        const val UNFINISHED_LIST = 3L
        const val ON_HOLD_LIST = 4L

        private val SCORE_LIST = (0..10)
            .flatMap { decimal ->
                when (decimal) {
                    0 -> listOf("-")
                    10 -> listOf("10.0")
                    else -> (0..9).map { fraction ->
                        "$decimal.$fraction"
                    }
                }
            }

        private const val SEARCH_ID_PREFIX = "id:"
    }

    private val interceptor by lazy { MangaUpdatesInterceptor(this) }

    private val api by lazy { MangaUpdatesApi(id, client, interceptor) }

    internal val userLibraryApi: MangaUpdatesUserLibraryApi
        get() = api

    internal val integrationApi: MangaUpdatesIntegrationApi = object : MangaUpdatesIntegrationApi {
        override suspend fun search(query: String): List<TrackSearch> =
            this@MangaUpdates.search(query)

        override suspend fun discover(
            orderBy: String,
            offset: Int,
            limit: Int,
        ): List<TrackSearch> =
            api.discover(orderBy, offset, limit).map { it.toTrackSearch(id) }

        override suspend fun getMangaDetails(id: Long): TrackSearch =
            api.getSeriesDetails(id)?.toTrackSearch(this@MangaUpdates.id)
                ?: error("MangaUpdates title not found: $id")

        override suspend fun collectionSearch(
            query: MangaUpdatesCollectionQuery,
        ): MangaUpdatesCollectionPage = api.collectionSearch(query, this@MangaUpdates.id)
    }

    override fun getLogo(): Int = R.drawable.brand_mangaupdates

    override fun getStatusList(): List<Long> {
        return listOf(READING_LIST, COMPLETE_LIST, ON_HOLD_LIST, UNFINISHED_LIST, WISH_LIST)
    }

    override fun getStatus(status: Long): StringResource? = when (status) {
        READING_LIST -> MR.strings.reading_list
        WISH_LIST -> MR.strings.wish_list
        COMPLETE_LIST -> MR.strings.complete_list
        ON_HOLD_LIST -> MR.strings.on_hold_list
        UNFINISHED_LIST -> MR.strings.unfinished_list
        else -> null
    }

    override fun getReadingStatus(): Long = READING_LIST

    override fun getRereadingStatus(): Long = -1

    override fun getCompletionStatus(): Long = COMPLETE_LIST

    override fun getScoreList(): List<String> = SCORE_LIST

    override fun indexToScore(index: Int): Double = if (index == 0) 0.0 else SCORE_LIST[index].toDouble()

    override fun displayScore(track: DomainTrack): String = track.score.toString()

    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        if (track.status != COMPLETE_LIST && didReadChapter) {
            track.status = READING_LIST
        }
        api.updateSeriesListItem(track)
        return track
    }

    override suspend fun delete(track: DomainTrack) {
        api.deleteSeriesFromList(track)
    }

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track {
        return try {
            val (series, rating) = api.getSeriesListItem(track)
            track.copyFrom(series, rating)
        } catch (_: Exception) {
            track.score = 0.0
            api.addSeriesToList(track, hasReadChapters)
            track
        }
    }

    override suspend fun search(query: String): List<TrackSearch> {
        if (query.startsWith(SEARCH_ID_PREFIX)) {
            return try {
                val stringId = query.substringAfter(SEARCH_ID_PREFIX).trim()
                val searchId = stringId.toLongOrNull() ?: stringId.toLong(36)

                api.getSeriesDetails(searchId)?.let { listOf(it.toTrackSearch(id)) } ?: emptyList()
            } catch (_: NumberFormatException) {
                emptyList()
            }
        }

        return api.search(query)
            .map {
                it.toTrackSearch(id)
            }
    }

    override suspend fun refresh(track: Track): Track {
        val (series, rating) = api.getSeriesListItem(track)
        return track.copyFrom(series, rating)
    }

    private fun Track.copyFrom(item: MUListItem, rating: MURating?): Track = apply {
        item.copyTo(this)
        score = rating?.rating ?: 0.0
    }

    override suspend fun login(username: String, password: String) {
        val authenticated = api.authenticate(username, password)
        interceptor.newAuth(authenticated.sessionToken)
        val currentUser = api.getCurrentUser()
        saveDisplayUsername(currentUser.username)
        saveCredentials(authenticated.uid.toString(), authenticated.sessionToken)
    }

    fun restoreSession(): String? {
        return trackPreferences.trackPassword(this).get().ifBlank { null }
    }
}
