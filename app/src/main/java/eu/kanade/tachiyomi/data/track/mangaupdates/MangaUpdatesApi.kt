package eu.kanade.tachiyomi.data.track.mangaupdates

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdates.Companion.READING_LIST
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdates.Companion.WISH_LIST
import eu.kanade.tachiyomi.data.track.mangaupdates.dto.MUContext
import eu.kanade.tachiyomi.data.track.mangaupdates.dto.MUCurrentUser
import eu.kanade.tachiyomi.data.track.mangaupdates.dto.MUListItem
import eu.kanade.tachiyomi.data.track.mangaupdates.dto.MULoginResponse
import eu.kanade.tachiyomi.data.track.mangaupdates.dto.MURating
import eu.kanade.tachiyomi.data.track.mangaupdates.dto.MURecord
import eu.kanade.tachiyomi.data.track.mangaupdates.dto.MUSearchResult
import eu.kanade.tachiyomi.data.track.mangaupdates.dto.MUUserList
import eu.kanade.tachiyomi.data.track.mangaupdates.dto.MUUserListSearchResponse
import eu.kanade.tachiyomi.data.track.mangaupdates.dto.MUUserListSearchResult
import eu.kanade.tachiyomi.network.DELETE
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.PUT
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy
import tachiyomi.domain.track.model.Track as DomainTrack

class MangaUpdatesApi(
    private val trackerId: Long,
    private val client: OkHttpClient,
    interceptor: MangaUpdatesInterceptor,
) : MangaUpdatesUserLibraryApi {
    private val json: Json by injectLazy()

    private val authClient by lazy {
        client.newBuilder()
            .addInterceptor(interceptor)
            .build()
    }

    override suspend fun getUserLibrary(): MangaUpdatesUserLibrarySnapshot {
        return withIOContext {
            val lists = getUserLists()
            val detailsById = mutableMapOf<Long, TrackSearch?>()
            val entries = mutableListOf<MangaUpdatesUserListEntry>()

            lists.forEach { list ->
                getUserListEntries(list.listId).forEach { result ->
                    val manga = if (result.seriesId in detailsById) {
                        detailsById[result.seriesId]
                    } else {
                        getSeriesDetails(result.seriesId)
                            ?.toTrackSearch(trackerId)
                            .also { detailsById[result.seriesId] = it }
                    } ?: return@forEach

                    val membership = result.metadata.userList
                    entries += MangaUpdatesUserListEntry(
                        manga = manga,
                        listId = list.listId,
                        progress = (
                            membership?.status?.chapter
                                ?: result.chapter
                                ?: 0
                            ).toDouble(),
                        score = result.metadata.userRating ?: 0.0,
                        addedAt = membership?.timeAdded?.asRfc3339,
                    )
                }
            }

            MangaUpdatesUserLibrarySnapshot(
                lists = lists.map { list ->
                    MangaUpdatesUserList(
                        id = list.listId,
                        title = list.title,
                        type = list.type,
                        custom = list.custom,
                    )
                },
                entries = entries,
            )
        }
    }

    private suspend fun getUserLists(): List<MUUserList> {
        return with(json) {
            authClient.newCall(GET("$BASE_URL/v1/lists"))
                .awaitSuccess()
                .parseAs()
        }
    }

    private suspend fun getUserListEntries(listId: Long): List<MUUserListSearchResult> {
        val entries = mutableListOf<MUUserListSearchResult>()
        var requestedPage: Int? = null

        while (true) {
            val body = buildJsonObject {
                requestedPage?.let { put("page", it) }
                put("perpage", USER_LIST_PAGE_SIZE)
            }
            val response = with(json) {
                authClient.newCall(
                    POST(
                        url = "$BASE_URL/v1/lists/$listId/search",
                        body = body.toString().toRequestBody(CONTENT_TYPE),
                    ),
                )
                    .awaitSuccess()
                    .parseAs<MUUserListSearchResponse>()
            }

            entries += response.results
            val reachedTotal = response.totalHits > 0 && entries.size >= response.totalHits
            if (response.results.isEmpty() || reachedTotal || response.results.size < USER_LIST_PAGE_SIZE) {
                break
            }

            val nextPage = response.page + 1
            check(requestedPage == null || nextPage > requestedPage!!) {
                "MangaUpdates list pagination did not advance for list $listId"
            }
            requestedPage = nextPage
        }

        return entries
    }

    suspend fun getSeriesListItem(track: Track): Pair<MUListItem, MURating?> {
        val listItem = with(json) {
            authClient.newCall(GET("$BASE_URL/v1/lists/series/${track.remote_id}"))
                .awaitSuccess()
                .parseAs<MUListItem>()
        }

        val rating = getSeriesRating(track)

        return listItem to rating
    }

    suspend fun addSeriesToList(track: Track, hasReadChapters: Boolean) {
        val status = if (hasReadChapters) READING_LIST else WISH_LIST
        val body = buildJsonArray {
            addJsonObject {
                putJsonObject("series") {
                    put("id", track.remote_id)
                }
                put("list_id", status)
            }
        }
        authClient.newCall(
            POST(
                url = "$BASE_URL/v1/lists/series",
                body = body.toString().toRequestBody(CONTENT_TYPE),
            ),
        )
            .awaitSuccess()
            .let {
                if (it.code == 200) {
                    track.status = status
                    track.last_chapter_read = 1.0
                }
            }
    }

    suspend fun updateSeriesListItem(track: Track) {
        val body = buildJsonArray {
            addJsonObject {
                putJsonObject("series") {
                    put("id", track.remote_id)
                }
                put("list_id", track.status)
                putJsonObject("status") {
                    put("chapter", track.last_chapter_read.toInt())
                }
            }
        }
        authClient.newCall(
            POST(
                url = "$BASE_URL/v1/lists/series/update",
                body = body.toString().toRequestBody(CONTENT_TYPE),
            ),
        )
            .awaitSuccess()

        updateSeriesRating(track)
    }

    suspend fun deleteSeriesFromList(track: DomainTrack) {
        val body = buildJsonArray {
            add(track.remoteId)
        }
        authClient.newCall(
            POST(
                url = "$BASE_URL/v1/lists/series/delete",
                body = body.toString().toRequestBody(CONTENT_TYPE),
            ),
        )
            .awaitSuccess()
    }

    private suspend fun getSeriesRating(track: Track): MURating? {
        return try {
            with(json) {
                authClient.newCall(GET("$BASE_URL/v1/series/${track.remote_id}/rating"))
                    .awaitSuccess()
                    .parseAs<MURating>()
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun updateSeriesRating(track: Track) {
        if (track.score < 0.0) return
        if (track.score != 0.0) {
            val body = buildJsonObject {
                put("rating", track.score)
            }
            authClient.newCall(
                PUT(
                    url = "$BASE_URL/v1/series/${track.remote_id}/rating",
                    body = body.toString().toRequestBody(CONTENT_TYPE),
                ),
            )
                .awaitSuccess()
        } else {
            authClient.newCall(
                DELETE(url = "$BASE_URL/v1/series/${track.remote_id}/rating"),
            )
                .awaitSuccess()
        }
    }

    suspend fun search(query: String): List<MURecord> {
        val body = buildJsonObject {
            put("search", query)
            put(
                "filter_types",
                buildJsonArray {
                    add("drama cd")
                    add("novel")
                },
            )
        }

        return with(json) {
            client.newCall(
                POST(
                    url = "$BASE_URL/v1/series/search",
                    body = body.toString().toRequestBody(CONTENT_TYPE),
                ),
            )
                .awaitSuccess()
                .parseAs<MUSearchResult>()
                .results
                .map { it.record }
        }
    }

    suspend fun discover(
        orderBy: String,
        offset: Int,
        limit: Int,
    ): List<MURecord> {
        if (limit <= 0) return emptyList()

        val requested = offset.coerceAtLeast(0) + limit
        val body = buildJsonObject {
            put("orderby", orderBy)
            put("perpage", requested)
            put("include_rank_metadata", true)
            put(
                "filter_types",
                buildJsonArray {
                    add("drama cd")
                    add("novel")
                },
            )
        }

        return with(json) {
            client.newCall(
                POST(
                    url = "$BASE_URL/v1/series/search",
                    body = body.toString().toRequestBody(CONTENT_TYPE),
                ),
            )
                .awaitSuccess()
                .parseAs<MUSearchResult>()
                .results
                .map { it.record }
                .drop(offset.coerceAtLeast(0))
                .take(limit)
        }
    }

    suspend fun getSeriesDetails(id: Long): MURecord? {
        return withIOContext {
            val url = "$BASE_URL/v1/series/$id"

            with(json) {
                val response = client.newCall(GET(url))
                    .await()

                if (response.code == 404) {
                    null
                } else {
                    response.parseAs<MURecord>()
                }
            }
        }
    }

    suspend fun authenticate(username: String, password: String): MUContext {
        val body = buildJsonObject {
            put("username", username)
            put("password", password)
        }
        return with(json) {
            client.newCall(
                PUT(
                    url = "$BASE_URL/v1/account/login",
                    body = body.toString().toRequestBody(CONTENT_TYPE),
                ),
            )
                .awaitSuccess()
                .parseAs<MULoginResponse>()
                .context
        }
    }

    suspend fun getCurrentUser(): MUCurrentUser {
        return with(json) {
            authClient.newCall(GET("$BASE_URL/v1/account/profile"))
                .awaitSuccess()
                .parseAs<MUCurrentUser>()
        }
    }

    companion object {
        private const val BASE_URL = "https://api.mangaupdates.com"
        private const val USER_LIST_PAGE_SIZE = 100

        private val CONTENT_TYPE = "application/json".toMediaType()
    }
}
