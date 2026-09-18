package tachiyomi.data.tsuzuki.kitsu.client

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMangaResponse
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuSingleMangaResponse
import tachiyomi.domain.tsuzuki.catalog.model.CatalogError
import java.io.IOException

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class KitsuHttpClient(
    private val client: OkHttpClient,
    private val json: Json,
) : KitsuClient {

    @Inject
    constructor(
        network: NetworkHelper,
        json: Json,
    ) : this(network.client, json)

    private val baseUrl: HttpUrl = "https://kitsu.io/api/edge/".toHttpUrl()

    private val kitsuJson: Json = Json(json) {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private fun requestBuilder(url: HttpUrl): Request.Builder {
        return Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/vnd.api+json")
            .header("Content-Type", "application/vnd.api+json")
    }

    override suspend fun searchManga(
        query: String?,
        offset: Int,
        limit: Int,
        sort: String?,
        status: String?,
    ): Result<KitsuMangaResponse> {
        val urlBuilder = baseUrl.newBuilder().addPathSegment("manga")
        if (!query.isNullOrBlank()) {
            urlBuilder.addQueryParameter("filter[text]", query.trim())
        }
        if (!status.isNullOrBlank()) {
            urlBuilder.addQueryParameter("filter[status]", status.trim())
        }
        if (!sort.isNullOrBlank()) {
            urlBuilder.addQueryParameter("sort", sort.trim())
        }
        urlBuilder.addQueryParameter("page[offset]", offset.toString())
        urlBuilder.addQueryParameter("page[limit]", limit.toString())

        return executeRequest(urlBuilder.build())
    }

    override suspend fun getTrendingManga(limit: Int): Result<KitsuMangaResponse> {
        val url = baseUrl.newBuilder()
            .addPathSegment("trending")
            .addPathSegment("manga")
            .addQueryParameter("limit", limit.toString())
            .build()

        return executeRequest(url)
    }

    override suspend fun getPopularManga(offset: Int, limit: Int): Result<KitsuMangaResponse> {
        return searchManga(
            query = null,
            offset = offset,
            limit = limit,
            sort = "-userCount",
            status = null,
        )
    }

    override suspend fun getMangaDetails(kitsuId: String): Result<KitsuSingleMangaResponse> {
        val url = baseUrl.newBuilder()
            .addPathSegment("manga")
            .addPathSegment(kitsuId)
            .build()

        return executeRequest(url)
    }

    override suspend fun getMangaById(id: String): Result<KitsuSingleMangaResponse> = getMangaDetails(id)

    private suspend inline fun <reified T> executeRequest(url: HttpUrl): Result<T> {
        return try {
            val response: Response = client.newCall(requestBuilder(url).build()).await()
            when (response.code) {
                in 200..299 -> {
                    val bodyString = response.body.string()
                    val parsed = kitsuJson.decodeFromString<T>(bodyString)
                    Result.success(parsed)
                }
                404 -> Result.failure(CatalogError.ItemNotFound(url.encodedPath))
                429 -> {
                    val retryAfter = response.header("Retry-After")?.toLongOrNull()
                    Result.failure(CatalogError.RateLimitExceeded(retryAfter))
                }
                in 500..599 -> Result.failure(CatalogError.ProviderUnavailable("Kitsu server error: ${response.code}"))
                else -> Result.failure(CatalogError.HttpError(response.code, response.message))
            }
        } catch (e: SerializationException) {
            Result.failure(CatalogError.SerializationError(e))
        } catch (e: IOException) {
            Result.failure(CatalogError.NetworkError(e))
        } catch (e: Exception) {
            Result.failure(CatalogError.ProviderUnavailable("Unexpected failure: ${e.message}", e))
        }
    }
}
