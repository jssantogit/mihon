package eu.kanade.tachiyomi.data.track.shikimori

import androidx.core.net.toUri
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.data.track.shikimori.dto.SMLibraryIdResponse
import eu.kanade.tachiyomi.data.track.shikimori.dto.SMOAuth
import eu.kanade.tachiyomi.data.track.shikimori.dto.SMSearchResult
import eu.kanade.tachiyomi.data.track.shikimori.dto.SMUser
import eu.kanade.tachiyomi.data.track.shikimori.dto.SMUserListResult
import eu.kanade.tachiyomi.data.track.shikimori.dto.SMUserResult
import eu.kanade.tachiyomi.network.DELETE
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.PUT
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy
import tachiyomi.domain.track.model.Track as DomainTrack

class ShikimoriApi(
    private val trackerId: Long,
    private val client: OkHttpClient,
    interceptor: ShikimoriInterceptor,
    private val clientIdProvider: () -> String,
    private val clientSecretProvider: () -> String,
) {

    private val json: Json by injectLazy()

    private val authClient = client.newBuilder().addInterceptor(interceptor).build()

    suspend fun addLibManga(track: Track, userId: String): Track {
        return withIOContext {
            with(json) {
                val payload = buildJsonObject {
                    putJsonObject("user_rate") {
                        put("user_id", userId)
                        put("target_id", track.remote_id)
                        put("target_type", "Manga")
                        put("chapters", track.last_chapter_read.toInt())
                        put("score", track.score.toInt())
                        put("status", track.toShikimoriStatus())
                    }
                }
                authClient.newCall(
                    POST(
                        "$API_URL/v2/user_rates",
                        body = payload.toString().toRequestBody(jsonMime),
                    ),
                ).awaitSuccess()
                    .parseAs<SMLibraryIdResponse>()
                    .let {
                        // save id of the entry for possible future delete request
                        track.library_id = it.id
                    }
                track
            }
        }
    }

    suspend fun updateLibManga(track: Track): Track {
        return withIOContext {
            val payload = buildJsonObject {
                putJsonObject("user_rate") {
                    put("chapters", track.last_chapter_read.toInt())
                    put("score", track.score.toInt())
                    put("status", track.toShikimoriStatus())
                }
            }

            with(json) {
                authClient.newCall(
                    PUT(
                        "$API_URL/v2/user_rates/${track.library_id}",
                        body = payload.toString().toRequestBody(jsonMime),
                    ),
                )
                    .awaitSuccess()
                    .parseAs<SMLibraryIdResponse>()
                    .let {
                        track.library_id = it.id
                    }
                track
            }
        }
    }

    suspend fun deleteLibManga(track: DomainTrack) {
        withIOContext {
            authClient
                .newCall(DELETE("$API_URL/v2/user_rates/${track.libraryId}"))
                .awaitSuccess()
        }
    }

    suspend fun search(search: String): List<TrackSearch> {
        return withIOContext {
            val query = $$"""
            |query($query: String) {
                |mangas(search: $query, limit: 20, kind:"!light_novel,!novel") {
                    |id
                    |name
                    |chapters
                    |kind
                    |poster {
                        |mainUrl
                    |}
                    |score
                    |url
                    |status
                    |airedOn {
                        |date
                    |}
                    |description
                    |personRoles {
                        |person {
                            |name
                        |}
                        |rolesEn
                    |}
                |}
            |}
            """.trimMargin()
            val payload = buildJsonObject {
                put("query", query)
                putJsonObject("variables") {
                    put("query", search)
                }
            }
            with(json) {
                authClient.newCall(
                    POST(
                        GRAPHQL_API_URL,
                        body = payload.toString().toRequestBody(jsonMime),
                    ),
                )
                    .awaitSuccess()
                    .parseAs<SMSearchResult>()
                    .data.mangas
                    .map { it.toTrack(trackerId) }
            }
        }
    }

    suspend fun getMangaDetails(id: Int): TrackSearch? {
        return withIOContext {
            val query = $$"""
            |query($query: String) {
                |mangas(ids: $query, limit: 1, kind:"!light_novel,!novel") {
                    |id
                    |name
                    |chapters
                    |kind
                    |poster {
                        |mainUrl
                    |}
                    |score
                    |url
                    |status
                    |airedOn {
                        |date
                    |}
                    |description
                    |personRoles {
                        |person {
                            |name
                        |}
                        |rolesEn
                    |}
                |}
            |}
            """.trimMargin()
            val payload = buildJsonObject {
                put("query", query)
                putJsonObject("variables") {
                    put("query", "$id")
                }
            }

            with(json) {
                authClient.newCall(
                    POST(
                        GRAPHQL_API_URL,
                        body = payload.toString().toRequestBody(jsonMime),
                    ),
                )
                    .awaitSuccess()
                    .parseAs<SMSearchResult>()
                    .data.mangas
                    .firstOrNull()
                    ?.toTrack(trackerId)
            }
        }
    }

    suspend fun findLibManga(track: Track): Track? {
        return withIOContext {
            val query = $$"""
                |query($id: String) {
                    |mangas(ids: $id, limit: 1) {
                        |id
                        |url
                        |name
                        |chapters
                        |userRate {
                            |id
                            |chapters
                            |status
                            |score
                        |}
                    |}
                |}
            """.trimMargin()

            val payload = buildJsonObject {
                put("query", query)
                putJsonObject("variables") {
                    put("id", track.remote_id.toString())
                }
            }
            with(json) {
                val listResult = authClient.newCall(
                    POST(
                        GRAPHQL_API_URL,
                        body = payload.toString().toRequestBody(jsonMime),
                    ),
                )
                    .awaitSuccess()
                    .parseAs<SMUserListResult>()
                    .data.mangas
                    .firstOrNull()

                // Shikimori has no user list query that allows query by ID, so we go via the "mangas" query & include
                // userRate data which will be null if the title is not in the user's list.
                // If it was removed on Shikimori and is still linked in the app, notify user via returning null here
                // which throws an exception at the Shikimori.refresh call
                if (listResult?.userRate == null) {
                    null
                } else {
                    listResult.toTrack(trackerId)
                }
            }
        }
    }

    suspend fun getCurrentUser(): SMUser {
        return with(json) {
            val query = """
            |{
                |currentUser {
                    |id
                    |nickname
                |}
            |}
            """.trimMargin()
            val payload = buildJsonObject {
                put("query", query)
            }
            authClient.newCall(
                POST(
                    GRAPHQL_API_URL,
                    body = payload.toString().toRequestBody(jsonMime),
                ),
            )
                .awaitSuccess()
                .parseAs<SMUserResult>()
                .data.currentUser
        }
    }

    suspend fun accessToken(code: String): SMOAuth {
        return withIOContext {
            with(json) {
                client.newCall(
                    accessTokenRequest(
                        code = code,
                        clientId = requireClientId(),
                        clientSecret = requireClientSecret(),
                    ),
                )
                    .awaitSuccess()
                    .parseAs()
            }
        }
    }

    private fun requireClientId(): String =
        clientIdProvider().trim().ifBlank { throw ShikimoriCredentialsMissing() }

    private fun requireClientSecret(): String =
        clientSecretProvider().trim().ifBlank { throw ShikimoriCredentialsMissing() }

    companion object {
        const val CALLBACK_URL = "tsuzuki://shikimori-auth"

        private const val BASE_URL = "https://shikimori.io"
        private const val API_URL = "$BASE_URL/api"
        private const val GRAPHQL_API_URL = "$BASE_URL/api/graphql"
        private const val OAUTH_URL = "$BASE_URL/oauth/token"
        private const val LOGIN_URL = "$BASE_URL/oauth/authorize"

        fun authUrl(clientId: String): String = LOGIN_URL.toHttpUrl()
            .newBuilder()
            .addQueryParameter("client_id", requireCredential(clientId))
            .addQueryParameter("redirect_uri", CALLBACK_URL)
            .addQueryParameter("response_type", "code")
            .build()
            .toString()

        internal fun accessTokenRequest(
            code: String,
            clientId: String,
            clientSecret: String,
        ) = POST(
            OAUTH_URL,
            body = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", requireCredential(clientId))
                .add("client_secret", requireCredential(clientSecret))
                .add("code", code)
                .add("redirect_uri", CALLBACK_URL)
                .build(),
        )

        fun refreshTokenRequest(
            token: String,
            clientId: String,
            clientSecret: String,
        ) = POST(
            OAUTH_URL,
            body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", requireCredential(clientId))
                .add("client_secret", requireCredential(clientSecret))
                .add("refresh_token", token)
                .build(),
        )

        private fun requireCredential(value: String): String =
            value.trim().ifBlank { throw ShikimoriCredentialsMissing() }
    }
}
