package eu.kanade.tachiyomi.data.track.bangumi

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.data.track.personalTokenSessionActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tachiyomi.i18n.MR
import tachiyomi.domain.track.model.Track as DomainTrack

data class BangumiCollectionQuery(
    val keyword: String = "",
    val sort: String,
    val tags: List<String> = emptyList(),
    val metaTags: List<String> = emptyList(),
    val airDate: List<String> = emptyList(),
    val rating: List<String> = emptyList(),
    val ratingCount: List<String> = emptyList(),
    val rank: List<String> = emptyList(),
    val nsfw: Boolean? = null,
    val offset: Int,
    val limit: Int,
) {
    init {
        require(sort in setOf("match", "heat", "rank", "score")) {
            "Unsupported Bangumi search sort: $sort"
        }
        require(offset >= 0) { "Bangumi offset must be non-negative" }
        require(limit in 1..50) { "Bangumi limit must be between 1 and 50" }
    }
}

data class BangumiCollectionPage(
    val items: List<TrackSearch>,
    val total: Int,
)

interface BangumiIntegrationApi {
    suspend fun search(query: String): List<TrackSearch>

    suspend fun browse(
        sort: String,
        offset: Int,
        limit: Int,
    ): List<TrackSearch>

    suspend fun collectionSearch(query: BangumiCollectionQuery): BangumiCollectionPage =
        BangumiCollectionPage(emptyList(), total = 0)

    suspend fun getMangaDetails(id: Int): TrackSearch
}

class Bangumi(id: Long) : BaseTracker(id, "Bangumi") {

    private val interceptor by lazy { BangumiInterceptor(this) }

    private val api by lazy { BangumiApi(id, client, interceptor) }

    internal val userLibraryApi: BangumiUserLibraryApi = object : BangumiUserLibraryApi {
        override suspend fun getUserLibrary(): List<BangumiUserListEntry> =
            api.getUserBookCollections(getUsername())
    }

    internal val integrationApi: BangumiIntegrationApi = object : BangumiIntegrationApi {
        override suspend fun search(query: String): List<TrackSearch> =
            this@Bangumi.search(query)

        override suspend fun browse(
            sort: String,
            offset: Int,
            limit: Int,
        ): List<TrackSearch> =
            api.browse(sort, offset, limit)

        override suspend fun collectionSearch(query: BangumiCollectionQuery): BangumiCollectionPage =
            api.collectionSearch(query)

        override suspend fun getMangaDetails(id: Int): TrackSearch =
            api.getMangaDetails(id) ?: error("Bangumi title not found: $id")
    }

    override val supportsPrivateTracking: Boolean = true

    override fun getScoreList(): List<String> = SCORE_LIST

    override fun displayScore(track: DomainTrack): String {
        return track.score.toInt().toString()
    }

    private suspend fun add(track: Track): Track {
        return api.addLibManga(track)
    }

    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        if (track.status != COMPLETED) {
            if (didReadChapter) {
                if (track.last_chapter_read.toLong() == track.total_chapters && track.total_chapters > 0) {
                    track.status = COMPLETED
                } else {
                    track.status = READING
                }
            }
        }

        return api.updateLibManga(track)
    }

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track {
        val statusTrack = api.statusLibManga(track, getUsername())
        return if (statusTrack != null) {
            track.copyPersonalFrom(statusTrack, copyRemotePrivate = false)
            track.library_id = statusTrack.library_id
            track.score = statusTrack.score
            track.last_chapter_read = statusTrack.last_chapter_read
            track.total_chapters = statusTrack.total_chapters
            if (track.status != COMPLETED) {
                track.status = if (hasReadChapters) READING else statusTrack.status
            }

            update(track)
        } else {
            // Set default fields if it's not found in the list
            track.status = if (hasReadChapters) READING else PLAN_TO_READ
            track.score = 0.0
            add(track)
        }
    }

    override suspend fun search(query: String): List<TrackSearch> {
        if (query.startsWith(SEARCH_ID_PREFIX)) {
            query.substringAfter(SEARCH_ID_PREFIX).trim().toIntOrNull()?.let { id ->
                return api.getMangaDetails(id)?.let { listOf(it) } ?: emptyList()
            }
        }

        return api.search(query)
    }

    override suspend fun refresh(track: Track): Track {
        val remoteStatusTrack = api.statusLibManga(track, getUsername()) ?: throw Exception("Could not find manga")
        track.copyPersonalFrom(remoteStatusTrack)
        return track
    }

    override fun getLogo() = R.drawable.brand_bangumi

    override fun getStatusList(): List<Long> {
        return listOf(READING, COMPLETED, ON_HOLD, DROPPED, PLAN_TO_READ)
    }

    override fun getStatus(status: Long): StringResource? = when (status) {
        READING -> MR.strings.reading
        PLAN_TO_READ -> MR.strings.plan_to_read
        COMPLETED -> MR.strings.completed
        ON_HOLD -> MR.strings.on_hold
        DROPPED -> MR.strings.dropped
        else -> null
    }

    override fun getReadingStatus(): Long = READING

    override fun getRereadingStatus(): Long = -1

    override fun getCompletionStatus(): Long = COMPLETED

    override suspend fun login(username: String, password: String) {
        loginWithPersonalAccessToken(password)
    }

    suspend fun loginWithPersonalAccessToken(accessToken: String) {
        val normalized = accessToken.trim().ifBlank { throw BangumiAccessTokenMissing() }
        logout()
        trackPreferences.integrationCredential(INTEGRATION_ID, ACCESS_TOKEN_KEY).set(normalized)

        try {
            val currentUser = api.getCurrentUser()
            saveDisplayUsername(currentUser.nickname?.takeIf { it.isNotBlank() } ?: currentUser.username)
            saveCredentials(currentUser.username, PERSONAL_TOKEN_MARKER)
        } catch (error: Throwable) {
            logout()
            throw error
        }
    }

    fun getPersonalAccessToken(): String =
        trackPreferences.integrationCredential(INTEGRATION_ID, ACCESS_TOKEN_KEY).get().trim()

    fun hasPersonalAccessToken(): Boolean = getPersonalAccessToken().isNotBlank()

    override val isLoggedIn: Boolean
        get() = personalTokenSessionActive(
            baseLoggedIn = super.isLoggedIn,
            password = getPassword(),
            accessToken = getPersonalAccessToken(),
            expectedPasswordMarker = PERSONAL_TOKEN_MARKER,
        )

    override val isLoggedInFlow: Flow<Boolean> by lazy {
        combine(
            super.isLoggedInFlow,
            trackPreferences.integrationCredential(INTEGRATION_ID, ACCESS_TOKEN_KEY).changes(),
        ) { baseLoggedIn, accessToken ->
            personalTokenSessionActive(
                baseLoggedIn = baseLoggedIn,
                password = getPassword(),
                accessToken = accessToken,
                expectedPasswordMarker = PERSONAL_TOKEN_MARKER,
            )
        }
    }

    override fun logout() {
        super.logout()
        trackPreferences.trackToken(this).delete()
        trackPreferences.integrationCredential(INTEGRATION_ID, ACCESS_TOKEN_KEY).delete()
    }

    companion object {
        const val PLAN_TO_READ = 1L
        const val COMPLETED = 2L
        const val READING = 3L
        const val ON_HOLD = 4L
        const val DROPPED = 5L

        private val SCORE_LIST = IntRange(0, 10)
            .map(Int::toString)

        private const val SEARCH_ID_PREFIX = "id:"
        private const val INTEGRATION_ID = "bangumi"
        private const val ACCESS_TOKEN_KEY = "access_token"
        private const val PERSONAL_TOKEN_MARKER = "personal_access_token"
    }
}
