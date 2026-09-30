package eu.kanade.tachiyomi.data.track.myanimelist

import android.net.Uri
import androidx.core.net.toUri
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALListItem
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALListItemStatus
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALManga
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALOAuth
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALSearchResult
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALUser
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALUserListPage
import eu.kanade.tachiyomi.data.track.myanimelist.dto.toTrackSearch
import eu.kanade.tachiyomi.network.DELETE
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale
import tachiyomi.domain.track.model.Track as DomainTrack

interface MalIntegrationApi {
    suspend fun search(query: String): List<TrackSearch>

    suspend fun getRanking(
        rankingType: String,
        offset: Int,
        limit: Int,
    ): List<TrackSearch>

    suspend fun getMangaDetails(id: Int): TrackSearch

    suspend fun getUserMangaList(): List<MalUserListEntry>
}

class MyAnimeListApi(
    private val trackerId: Long,
    private val client: OkHttpClient,
    interceptor: MyAnimeListInterceptor,
    private val clientIdProvider: () -> String,
) : MalIntegrationApi {

    private val json: Json by injectLazy()

    private val publicClient = client.newBuilder()
        .addInterceptor { chain ->
            chain.proceed(
                authorizeRequest(
                    request = chain.request(),
                    clientId = requireClientId(),
                    accessToken = null,
                ),
            )
        }
        .build()
    private val authClient = client.newBuilder().addInterceptor(interceptor).build()

    suspend fun getAccessToken(authCode: String, codeVerifier: String): MALOAuth {
        return withIOContext {
            with(json) {
                client.newCall(
                    accessTokenRequest(
                        authCode = authCode,
                        codeVerifier = codeVerifier,
                        clientId = requireClientId(),
                    ),
                )
                    .awaitSuccess()
                    .parseAs()
            }
        }
    }

    suspend fun getCurrentUser(): String {
        return withIOContext {
            val request = Request.Builder()
                .url("$BASE_API_URL/users/@me")
                .get()
                .build()
            with(json) {
                authClient.newCall(request)
                    .awaitSuccess()
                    .parseAs<MALUser>()
                    .name
            }
        }
    }

    override suspend fun search(query: String): List<TrackSearch> {
        return withIOContext {
            val url = "$BASE_API_URL/manga".toUri().buildUpon()
                // MAL API throws a 400 when the query is over 64 characters...
                .appendQueryParameter("q", query.take(64))
                .appendQueryParameter("nsfw", "true")
                .appendQueryParameter("fields", SEARCH_FIELDS)
                .build()
            with(json) {
                publicClient.newCall(GET(url.toString()))
                    .awaitSuccess()
                    .parseAs<MALSearchResult>()
                    .data
                    .filter { !(it.node.mediaType.contains("novel")) }
                    .map { it.node.toTrackSearch(trackerId) }
            }
        }
    }

    override suspend fun getRanking(
        rankingType: String,
        offset: Int,
        limit: Int,
    ): List<TrackSearch> {
        return withIOContext {
            val url = "$BASE_API_URL/manga/ranking".toUri().buildUpon()
                .appendQueryParameter("ranking_type", rankingType)
                .appendQueryParameter("offset", offset.coerceAtLeast(0).toString())
                .appendQueryParameter("limit", limit.coerceAtLeast(0).toString())
                .appendQueryParameter("nsfw", "true")
                .appendQueryParameter("fields", SEARCH_FIELDS)
                .build()
            with(json) {
                publicClient.newCall(GET(url.toString()))
                    .awaitSuccess()
                    .parseAs<MALSearchResult>()
                    .data
                    .filter { !(it.node.mediaType.contains("novel")) }
                    .map { it.node.toTrackSearch(trackerId) }
            }
        }
    }

    override suspend fun getMangaDetails(id: Int): TrackSearch {
        return withIOContext {
            val url = "$BASE_API_URL/manga".toUri().buildUpon()
                .appendPath(id.toString())
                .appendQueryParameter("fields", SEARCH_FIELDS)
                .build()
            with(json) {
                publicClient.newCall(GET(url.toString()))
                    .awaitSuccess()
                    .parseAs<MALManga>()
                    .let { it.toTrackSearch(trackerId) }
            }
        }
    }

    suspend fun updateItem(track: Track): Track {
        return withIOContext {
            val formBodyBuilder = FormBody.Builder()
                .add("status", track.toMyAnimeListStatus() ?: "reading")
                .add("is_rereading", (track.status == MyAnimeList.REREADING).toString())
                .add("score", track.score.toString())
                .add("num_chapters_read", track.last_chapter_read.toInt().toString())
            convertToIsoDate(track.started_reading_date)?.let {
                formBodyBuilder.add("start_date", it)
            }
            convertToIsoDate(track.finished_reading_date)?.let {
                formBodyBuilder.add("finish_date", it)
            }

            val request = Request.Builder()
                .url(mangaUrl(track.remote_id).toString())
                .put(formBodyBuilder.build())
                .build()
            with(json) {
                val response = authClient
                    .newCall(request)
                    .await()

                if (!response.isSuccessful) {
                    if (response.body.string().contains("invalid_content")) {
                        // MAL returns unapproved titles in search but does not allow adding them to the list
                        // returns 400 with this body: {"message":"Invalid content","error":"invalid_content"}
                        // These unapproved titles cannot be filtered out in search and are also returned by the
                        // endpoint we use for id prefix search
                        throw MALTitleNotApproved()
                    } else {
                        throw HttpException(response.code)
                    }
                }

                response
                    .parseAs<MALListItemStatus>()
                    .let { parseMangaItem(it, track) }
            }
        }
    }

    suspend fun deleteItem(track: DomainTrack) {
        withIOContext {
            authClient
                .newCall(DELETE(mangaUrl(track.remoteId).toString()))
                .awaitSuccess()
        }
    }

    suspend fun findListItem(track: Track): Track? {
        return withIOContext {
            val uri = "$BASE_API_URL/manga".toUri().buildUpon()
                .appendPath(track.remote_id.toString())
                .appendQueryParameter("fields", "num_chapters,my_list_status{start_date,finish_date}")
                .build()
            with(json) {
                authClient.newCall(GET(uri.toString()))
                    .awaitSuccess()
                    .parseAs<MALListItem>()
                    .let { item ->
                        track.total_chapters = item.numChapters
                        item.myListStatus?.let { parseMangaItem(it, track) }
                    }
            }
        }
    }

    suspend fun findListItems(query: String): List<TrackSearch> {
        return getUserMangaList()
            .filter { it.manga.title.contains(query, ignoreCase = true) }
            .map(MalUserListEntry::manga)
    }

    override suspend fun getUserMangaList(): List<MalUserListEntry> {
        return withIOContext {
            val entries = mutableListOf<MalUserListEntry>()
            var offset = 0
            do {
                val page = getUserListPage(offset)
                entries += page.data.map { entry ->
                    MalUserListEntry(
                        manga = entry.node.toTrackSearch(trackerId),
                        status = entry.listStatus.status,
                        progress = entry.listStatus.numChaptersRead,
                        score = entry.listStatus.score.toDouble(),
                        updatedAt = entry.listStatus.updatedAt,
                    )
                }
                offset += LIST_PAGINATION_AMOUNT
            } while (!page.paging.next.isNullOrBlank())
            entries
        }
    }

    private suspend fun getUserListPage(offset: Int): MALUserListPage {
        val urlBuilder = "$BASE_API_URL/users/@me/mangalist".toUri().buildUpon()
            .appendQueryParameter("fields", SEARCH_FIELDS)
            .appendQueryParameter("limit", LIST_PAGINATION_AMOUNT.toString())
        if (offset > 0) {
            urlBuilder.appendQueryParameter("offset", offset.toString())
        }

        val request = Request.Builder()
            .url(urlBuilder.build().toString())
            .get()
            .build()
        return with(json) {
            authClient.newCall(request)
                .awaitSuccess()
                .parseAs()
        }
    }

    private fun parseMangaItem(listStatus: MALListItemStatus, track: Track): Track {
        return track.apply {
            val isRereading = listStatus.isRereading
            status = if (isRereading) MyAnimeList.REREADING else getStatus(listStatus.status)
            last_chapter_read = listStatus.numChaptersRead
            score = listStatus.score.toDouble()
            listStatus.startDate?.let { started_reading_date = parseDate(it) }
            listStatus.finishDate?.let { finished_reading_date = parseDate(it) }
        }
    }

    private fun parseDate(isoDate: String): Long {
        val pattern = when (isoDate.length) {
            10 -> "yyyy-MM-dd"
            7 -> "yyyy-MM"
            4 -> "yyyy"
            else -> throw IllegalArgumentException("Unsupported date format: \"$isoDate\"")
        }
        return SimpleDateFormat(pattern, Locale.US).parse(isoDate)?.time ?: 0L
    }

    private fun requireClientId(): String = clientIdProvider()
        .trim()
        .ifBlank { throw MALClientIdMissing() }

    private fun convertToIsoDate(epochTime: Long): String? {
        if (epochTime == 0L) {
            return ""
        }
        return try {
            val outputDf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            outputDf.format(epochTime)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val CALLBACK_URL = "tsuzuki://myanimelist-auth"

        private const val BASE_OAUTH_URL = "https://myanimelist.net/v1/oauth2"
        private const val BASE_API_URL = "https://api.myanimelist.net/v2"
        private const val CLIENT_ID_HEADER = "X-MAL-CLIENT-ID"

        private const val SEARCH_FIELDS =
            "id,title,synopsis,num_chapters,num_volumes,mean,num_scoring_users,main_picture,status,media_type,start_date,end_date,authors{first_name,last_name},genres"

        private const val LIST_PAGINATION_AMOUNT = 250

        fun authUrl(clientId: String, codeVerifier: String): String = "$BASE_OAUTH_URL/authorize".toHttpUrl()
            .newBuilder()
            .addQueryParameter("client_id", requireClientId(clientId))
            .addQueryParameter("code_challenge", requireCodeVerifier(codeVerifier))
            .addQueryParameter("response_type", "code")
            .build()
            .toString()

        internal fun accessTokenRequest(
            authCode: String,
            codeVerifier: String,
            clientId: String,
        ): Request = POST(
            "$BASE_OAUTH_URL/token",
            body = FormBody.Builder()
                .add("client_id", requireClientId(clientId))
                .add("code", authCode)
                .add("code_verifier", requireCodeVerifier(codeVerifier))
                .add("grant_type", "authorization_code")
                .build(),
        )

        fun mangaUrl(id: Long): Uri = "$BASE_API_URL/manga".toUri().buildUpon()
            .appendPath(id.toString())
            .appendPath("my_list_status")
            .build()

        fun refreshTokenRequest(oauth: MALOAuth, clientId: String): Request {
            val formBody: RequestBody = FormBody.Builder()
                .add("client_id", requireClientId(clientId))
                .add("refresh_token", oauth.refreshToken)
                .add("grant_type", "refresh_token")
                .build()

            val headers = Headers.Builder()
                .add("Authorization", "Bearer ${oauth.accessToken}")
                .build()

            return POST("$BASE_OAUTH_URL/token", body = formBody, headers = headers)
        }

        internal fun authorizeRequest(
            request: Request,
            clientId: String,
            accessToken: String?,
        ): Request {
            val builder = request.newBuilder()
                .header(CLIENT_ID_HEADER, requireClientId(clientId))

            accessToken
                ?.takeIf(String::isNotBlank)
                ?.let { builder.header("Authorization", "Bearer $it") }

            return builder.build()
        }

        private fun requireClientId(clientId: String): String =
            clientId.trim().ifBlank { throw MALClientIdMissing() }

        private fun requireCodeVerifier(codeVerifier: String): String =
            codeVerifier.trim().ifBlank { throw MALPkceVerifierMissing() }
    }
}
