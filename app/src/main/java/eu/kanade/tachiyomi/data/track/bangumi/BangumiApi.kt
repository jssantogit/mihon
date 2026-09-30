package eu.kanade.tachiyomi.data.track.bangumi

import androidx.core.net.toUri
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.bangumi.dto.BGMCollectionPage
import eu.kanade.tachiyomi.data.track.bangumi.dto.BGMCollectionResponse
import eu.kanade.tachiyomi.data.track.bangumi.dto.BGMSearchResult
import eu.kanade.tachiyomi.data.track.bangumi.dto.BGMSubject
import eu.kanade.tachiyomi.data.track.bangumi.dto.BGMUser
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.CacheControl
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy

class BangumiApi(
    private val trackerId: Long,
    private val client: OkHttpClient,
    interceptor: BangumiInterceptor,
) {

    private val json: Json by injectLazy()

    private val authClient = client.newBuilder().addInterceptor(interceptor).build()

    suspend fun addLibManga(track: Track): Track {
        return withIOContext {
            val url = "$API_URL/v0/users/-/collections/${track.remote_id}"
            val body = buildJsonObject {
                put("type", track.toApiStatus())
                put("rate", track.score.toInt().coerceIn(0, 10))
                put("ep_status", track.last_chapter_read.toInt())
                put("private", track.private)
            }
                .toString()
                .toRequestBody()
            // Returns with 202 Accepted on success with no body
            authClient.newCall(POST(url, body = body, headers = headersOf("Content-Type", APP_JSON)))
                .awaitSuccess()
            track
        }
    }

    suspend fun updateLibManga(track: Track): Track {
        return withIOContext {
            val url = "$API_URL/v0/users/-/collections/${track.remote_id}"
            val body = buildJsonObject {
                put("type", track.toApiStatus())
                put("rate", track.score.toInt().coerceIn(0, 10))
                put("ep_status", track.last_chapter_read.toInt())
                put("private", track.private)
            }
                .toString()
                .toRequestBody()

            val request = Request.Builder()
                .url(url)
                .patch(body)
                .headers(headersOf("Content-Type", APP_JSON))
                .build()
            // Returns with 204 No Content
            authClient.newCall(request)
                .awaitSuccess()

            track
        }
    }

    suspend fun search(search: String): List<TrackSearch> {
        // This API is marked as experimental in the documentation
        // but that has been the case since 2022 with few significant
        // changes to the schema for this endpoint since
        // "实验性 API， 本 schema 和实际的 API 行为都可能随时发生改动"
        return withIOContext {
            val url = "$API_URL/v0/search/subjects?limit=20"
            val body = buildJsonObject {
                put("keyword", search)
                put("sort", "match")
                putJsonObject("filter") {
                    putJsonArray("type") {
                        add(1) // "Book" (书籍) type
                    }
                }
            }
                .toString()
                .toRequestBody()
            with(json) {
                authClient.newCall(POST(url, body = body, headers = headersOf("Content-Type", APP_JSON)))
                    .awaitSuccess()
                    .parseAs<BGMSearchResult>()
                    .data
                    .filter { it.platform == null || it.platform == "漫画" }
                    .map { it.toTrackSearch(trackerId) }
            }
        }
    }

    suspend fun browse(
        sort: String,
        offset: Int,
        limit: Int,
    ): List<TrackSearch> {
        if (limit <= 0) return emptyList()

        return withIOContext {
            val url = "$API_URL/v0/subjects".toUri().buildUpon()
                .appendQueryParameter("type", "1")
                .appendQueryParameter("sort", sort)
                .appendQueryParameter("offset", offset.coerceAtLeast(0).toString())
                .appendQueryParameter("limit", limit.toString())
                .build()

            with(json) {
                authClient.newCall(GET(url.toString(), headers = headersOf("Content-Type", APP_JSON)))
                    .awaitSuccess()
                    .parseAs<BGMSearchResult>()
                    .data
                    .filter { it.platform == null || it.platform == "漫画" }
                    .map { it.toTrackSearch(trackerId) }
            }
        }
    }

    suspend fun getMangaDetails(id: Int): TrackSearch? {
        return withIOContext {
            val url = "$API_URL/v0/subjects/$id"

            with(json) {
                authClient.newCall(GET(url, headers = headersOf("Content-Type", APP_JSON)))
                    .awaitSuccess()
                    .parseAs<BGMSubject>()
                    .takeIf { it.platform == null || it.platform == "漫画" }
                    ?.toTrackSearch(trackerId)
            }
        }
    }

    suspend fun getUserBookCollections(username: String): List<BangumiUserListEntry> {
        return withIOContext {
            val entries = mutableListOf<BangumiUserListEntry>()
            var offset = 0

            while (true) {
                val url = "$API_URL/v0/users/$username/collections".toUri().buildUpon()
                    .appendQueryParameter("subject_type", BANGUMI_BOOK_SUBJECT_TYPE.toString())
                    .appendQueryParameter("limit", COLLECTION_PAGE_SIZE.toString())
                    .appendQueryParameter("offset", offset.toString())
                    .build()

                val page = with(json) {
                    authClient.newCall(
                        GET(
                            url.toString(),
                            cache = CacheControl.FORCE_NETWORK,
                            headers = headersOf("Content-Type", APP_JSON),
                        ),
                    )
                        .awaitSuccess()
                        .parseAs<BGMCollectionPage>()
                }

                page.data.mapNotNullTo(entries) { collection ->
                    val subjectId = collection.subjectId ?: collection.subject?.id
                        ?: return@mapNotNullTo null
                    // The collection endpoint only exposes SlimSubject for the broad "Book" type.
                    // Resolve full details so novels/art books are filtered consistently with the
                    // rest of the Bangumi manga integration and the canonical format is retained.
                    val manga = getMangaDetails(subjectId.toInt())
                        ?: return@mapNotNullTo null
                    val collectionType = collection.type ?: return@mapNotNullTo null

                    BangumiUserListEntry(
                        manga = manga,
                        collectionType = collectionType,
                        progress = collection.epStatus?.toDouble() ?: 0.0,
                        score = collection.rate?.toDouble() ?: 0.0,
                        updatedAt = collection.updatedAt,
                    )
                }

                if (page.data.isEmpty() || offset + page.data.size >= page.total) break
                val nextOffset = offset + page.data.size
                check(nextOffset > offset) { "Bangumi collection pagination did not advance" }
                offset = nextOffset
            }

            entries
        }
    }

    suspend fun statusLibManga(track: Track, username: String): Track? {
        return withIOContext {
            val url = "$API_URL/v0/users/$username/collections/${track.remote_id}"
            with(json) {
                try {
                    authClient.newCall(GET(url, cache = CacheControl.FORCE_NETWORK))
                        .awaitSuccess()
                        .parseAs<BGMCollectionResponse>()
                        .let {
                            track.status = it.getStatus()
                            track.last_chapter_read = it.epStatus?.toDouble() ?: 0.0
                            track.score = it.rate?.toDouble() ?: 0.0
                            track.total_chapters = it.subject?.eps?.toLong() ?: 0L
                            track
                        }
                } catch (e: HttpException) {
                    if (e.code == 404) { // "subject is not collected by user"
                        null
                    } else {
                        throw e
                    }
                }
            }
        }
    }

    suspend fun getCurrentUser(): BGMUser {
        return withIOContext {
            with(json) {
                authClient.newCall(GET("$API_URL/v0/me"))
                    .awaitSuccess()
                    .parseAs<BGMUser>()
            }
        }
    }

    companion object {
        private const val API_URL = "https://api.bgm.tv"
        private const val APP_JSON = "application/json"
        private const val BANGUMI_BOOK_SUBJECT_TYPE = 1
        private const val COLLECTION_PAGE_SIZE = 50

        internal fun authorizeRequest(
            request: Request,
            accessToken: String,
        ): Request {
            val token = accessToken.trim().ifBlank { throw BangumiAccessTokenMissing() }
            return request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
    }
}

class BangumiAccessTokenMissing : IllegalStateException(
    "Bangumi: enter a personal access token before connecting your account.",
)
