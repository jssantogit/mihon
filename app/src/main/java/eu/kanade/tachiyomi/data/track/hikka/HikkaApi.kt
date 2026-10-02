package eu.kanade.tachiyomi.data.track.hikka

import androidx.core.net.toUri
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.hikka.dto.HKManga
import eu.kanade.tachiyomi.data.track.hikka.dto.HKMangaPagination
import eu.kanade.tachiyomi.data.track.hikka.dto.HKOAuth
import eu.kanade.tachiyomi.data.track.hikka.dto.HKRead
import eu.kanade.tachiyomi.data.track.hikka.dto.HKUser
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.DELETE
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.PUT
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy
import tachiyomi.domain.track.model.Track as DomainTrack

class HikkaApi(
    private val trackerId: Long,
    private val client: OkHttpClient,
    interceptor: HikkaInterceptor,
    private val clientSecretProvider: () -> String,
) : HikkaIntegrationApi {
    suspend fun getCurrentUser(): HKUser {
        return withIOContext {
            val request = Request.Builder()
                .url("${BASE_API_URL}/user/me")
                .get()
                .build()
            with(json) {
                authClient.newCall(request)
                    .awaitSuccess()
                    .parseAs<HKUser>()
            }
        }
    }

    suspend fun accessToken(reference: String): HKOAuth {
        return withIOContext {
            with(json) {
                client.newCall(
                    authTokenCreate(
                        requestReference = reference,
                        clientSecret = requireClientSecret(),
                    ),
                )
                    .awaitSuccess()
                    .parseAs<HKOAuth>()
            }
        }
    }

    suspend fun searchManga(query: String): List<TrackSearch> =
        searchPublic(query).map { it.toTrack(trackerId) }

    override suspend fun lookupGenres(): List<Pair<String, String>> = withIOContext {
        val element = with(json) {
            client.newCall(GET("$BASE_API_URL/genres"))
                .awaitSuccess()
                .parseAs<JsonElement>()
        }
        element.lookupPairs(
            labelKeys = listOf("name_ua", "name", "title", "label"),
            valueKeys = listOf("slug", "name", "id"),
        )
    }

    override suspend fun searchPublic(query: String): List<HKManga> =
        collectionSearch(
            HikkaCollectionQuery(
                query = query,
                sort = "score:desc",
                page = 1,
                size = 50,
            ),
        ).items

    override suspend fun collectionSearch(query: HikkaCollectionQuery): HikkaCollectionPage {
        return withIOContext {
            val url = "$BASE_API_URL/manga".toUri().buildUpon()
                .appendQueryParameter("page", query.page.toString())
                .appendQueryParameter("size", query.size.toString())
                .build()

            val payload = buildJsonObject {
                query.yearFrom?.let { from ->
                    put(
                        "years",
                        buildJsonArray {
                            add(from)
                            add(query.yearTo ?: from)
                        },
                    )
                }
                put(
                    "media_type",
                    buildJsonArray {
                        query.mediaTypes.forEach(::add)
                    },
                )
                put(
                    "status",
                    buildJsonArray {
                        query.statuses.forEach(::add)
                    },
                )
                put("only_translated", query.onlyTranslated ?: false)
                put(
                    "magazines",
                    buildJsonArray {
                        query.magazines.forEach(::add)
                    },
                )
                put(
                    "genres",
                    buildJsonArray {
                        query.genres.forEach(::add)
                    },
                )
                put(
                    "score",
                    buildJsonArray {
                        add(query.malScoreFrom ?: 0.0)
                        add(query.malScoreTo ?: 10.0)
                    },
                )
                put(
                    "native_score",
                    buildJsonArray {
                        add(query.nativeScoreFrom ?: 0.0)
                        add(query.nativeScoreTo ?: 10.0)
                    },
                )
                put("query", query.query.orEmpty())
                put(
                    "sort",
                    buildJsonArray {
                        add(query.sort)
                    },
                )
            }

            val response = with(json) {
                client.newCall(POST(url.toString(), body = payload.toString().toRequestBody(jsonMime)))
                    .awaitSuccess()
                    .parseAs<HKMangaPagination>()
            }
            HikkaCollectionPage(
                items = response.list,
                page = response.pagination.page,
                pages = response.pagination.pages,
                total = response.pagination.total,
            )
        }
    }

    suspend fun getMangaDetails(slug: String): TrackSearch? =
        getMangaDetailsPublic(slug)?.toTrack(trackerId)

    override suspend fun getMangaDetailsPublic(slug: String): HKManga? {
        return withIOContext {
            val url = "$BASE_API_URL/manga/$slug"

            with(json) {
                val response = client.newCall(GET(url))
                    .await()

                if (response.code == 404) {
                    null
                } else {
                    response.parseAs<HKManga>()
                }
            }
        }
    }

    suspend fun getRead(track: Track): HKRead? {
        return withIOContext {
            val slug = track.tracking_url.split("/")[4]
            val url = "$BASE_API_URL/read/manga/$slug".toUri().buildUpon().build()
            with(json) {
                try {
                    authClient.newCall(GET(url.toString()))
                        .awaitSuccess()
                        .parseAs<HKRead>()
                } catch (e: HttpException) {
                    if (e.code == 404) {
                        null
                    } else {
                        throw e
                    }
                }
            }
        }
    }

    suspend fun getManga(track: Track): TrackSearch {
        return withIOContext {
            val slug = track.tracking_url.split("/")[4]
            val url = "$BASE_API_URL/manga/$slug".toUri().buildUpon()
                .build()

            with(json) {
                authClient.newCall(GET(url.toString()))
                    .awaitSuccess()
                    .parseAs<HKManga>()
                    .toTrack(trackerId)
            }
        }
    }

    suspend fun deleteUserManga(track: DomainTrack) {
        return withIOContext {
            val slug = track.remoteUrl.split("/")[4]

            val url = "$BASE_API_URL/read/manga/$slug".toUri().buildUpon()
                .build()

            authClient.newCall(DELETE(url.toString()))
                .awaitSuccess()
        }
    }

    suspend fun addUserManga(track: Track): Track {
        return withIOContext {
            val slug = track.tracking_url.split("/")[4]

            val url = "$BASE_API_URL/read/manga/$slug".toUri().buildUpon()
                .build()

            var rereads = getRead(track)?.rereads ?: 0
            if (track.status == Hikka.REREADING && rereads == 0) {
                rereads = 1
            }

            val payload = buildJsonObject {
                put("note", "")
                put("chapters", track.last_chapter_read.toInt())
                put("volumes", 0)
                put("rereads", rereads)
                put("score", track.score.toInt())
                put("status", track.toApiStatus())
                put("start_date", if (track.started_reading_date > 0L) track.started_reading_date / 1000 else null)
                put("end_date", if (track.finished_reading_date > 0L) track.finished_reading_date / 1000 else null)
            }

            with(json) {
                authClient.newCall(PUT(url.toString(), body = payload.toString().toRequestBody(jsonMime)))
                    .awaitSuccess()
                    .parseAs<HKRead>()
                    .toTrack(trackerId)
            }
        }
    }

    suspend fun updateUserManga(track: Track): Track = addUserManga(track)

    private val json: Json by injectLazy()
    private val authClient = client.newBuilder().addInterceptor(interceptor).build()

    private fun requireClientSecret(): String =
        clientSecretProvider().trim().ifBlank { throw HikkaCredentialsMissing() }

    private fun JsonElement.lookupPairs(
        labelKeys: List<String>,
        valueKeys: List<String>,
    ): List<Pair<String, String>> {
        val elements = when (this) {
            is JsonArray -> this
            is JsonObject -> values.firstOrNull { it is JsonArray } as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return elements.mapNotNull { entry ->
            when (entry) {
                is JsonPrimitive ->
                    entry.contentOrNull
                        ?.takeIf(String::isNotBlank)
                        ?.let { it to it }
                is JsonObject -> {
                    val label = labelKeys.asSequence()
                        .mapNotNull { key -> (entry[key] as? JsonPrimitive)?.contentOrNull }
                        .firstOrNull(String::isNotBlank)
                    val value = valueKeys.asSequence()
                        .mapNotNull { key -> (entry[key] as? JsonPrimitive)?.contentOrNull }
                        .firstOrNull(String::isNotBlank)
                    if (label != null && value != null) label to value else null
                }
                else -> null
            }
        }.distinctBy { it.second }
    }

    companion object {
        const val BASE_API_URL = "https://api.hikka.io"
        const val BASE_URL = "https://hikka.io"
        private const val SCOPE = "readlist,read:user-details"

        fun authUrl(clientReference: String): String = "$BASE_URL/oauth".toHttpUrl()
            .newBuilder()
            .addQueryParameter(
                "reference",
                clientReference.trim().ifBlank { throw HikkaCredentialsMissing() },
            )
            .addQueryParameter("scope", SCOPE)
            .build()
            .toString()

        fun refreshTokenRequest(accessToken: String): Request {
            val headers = Headers.Builder()
                .add("auth", accessToken)
                .build()

            return GET("$BASE_API_URL/user/me", headers = headers) // Any request with auth
        }

        fun authTokenCreate(
            requestReference: String,
            clientSecret: String,
        ): Request {
            val payload = buildJsonObject {
                put("request_reference", requestReference)
                put(
                    "client_secret",
                    clientSecret.trim().ifBlank { throw HikkaCredentialsMissing() },
                )
            }
            return POST("$BASE_API_URL/auth/token", body = payload.toString().toRequestBody(jsonMime))
        }

        fun authTokenInfo(accessToken: String): Request {
            val headers = Headers.Builder()
                .add("auth", accessToken)
                .build()

            return GET("$BASE_API_URL/auth/token/info", headers = headers)
        }
    }
}

class HikkaCredentialsMissing : IllegalStateException(
    "Hikka: configure your own application Reference and Client Secret before connecting.",
)
