package tachiyomi.data.tsuzuki.kitsu

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import tachiyomi.data.tsuzuki.kitsu.client.KitsuHttpClient
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMangaResponse
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuSingleMangaResponse
import tachiyomi.domain.tsuzuki.catalog.model.CatalogError
import java.io.IOException

class KitsuDtoSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun `parse search manga response fixture successfully`() {
        val jsonString = javaClass.getResource("/kitsu/kitsu_manga_search_berserk.json")!!.readText()
        val response = json.decodeFromString<KitsuMangaResponse>(jsonString)

        response.data.size shouldBe 1
        val item = response.data.first()
        item.id shouldBe "1234"
        item.type shouldBe "manga"
        item.attributes.canonicalTitle shouldBe "Berserk"
        item.attributes.averageRating shouldBe "84.56"
        item.attributes.userCount shouldBe 42000
        item.attributes.posterImage?.medium shouldNotBe null
        item.attributes.coverImage?.large shouldNotBe null
        response.meta?.count shouldBe 1
        response.links?.first shouldNotBe null
    }

    @Test
    fun `parse trending manga response fixture successfully`() {
        val jsonString = javaClass.getResource("/kitsu/kitsu_trending_manga.json")!!.readText()
        val response = json.decodeFromString<KitsuMangaResponse>(jsonString)

        response.data.size shouldBe 1
        val item = response.data.first()
        item.id shouldBe "5678"
        item.attributes.canonicalTitle shouldBe "Chainsaw Man"
        item.attributes.averageRating shouldBe "83.12"
        item.attributes.posterImage?.medium shouldNotBe null
    }

    @Test
    fun `parse single manga details response successfully`() {
        val jsonString = javaClass.getResource("/kitsu/kitsu_manga_details_single.json")!!.readText()
        val response = json.decodeFromString<KitsuSingleMangaResponse>(jsonString)

        response.data.id shouldBe "1234"
        response.data.attributes.canonicalTitle shouldBe "Berserk"
        response.data.attributes.averageRating shouldBe "84.56"
    }

    @Test
    fun `http client maps 404 response to ItemNotFound`() = runTest {
        val client = mockOkHttpClient(statusCode = 404, responseBody = "{\"errors\":[{\"title\":\"Not Found\"}]}")
        val kitsuClient = KitsuHttpClient(client, json)

        val result = kitsuClient.getMangaDetails("999999")
        result.isFailure shouldBe true
        result.exceptionOrNull().shouldBeInstanceOf<CatalogError.ItemNotFound>()
    }

    @Test
    fun `http client maps 429 response to RateLimitExceeded with Retry-After header`() = runTest {
        val client = mockOkHttpClient(
            statusCode = 429,
            responseBody = "{\"errors\":[{\"title\":\"Too Many Requests\"}]}",
            headers = mapOf("Retry-After" to "30"),
        )
        val kitsuClient = KitsuHttpClient(client, json)

        val result = kitsuClient.searchManga("test", 0, 10, null)
        result.isFailure shouldBe true
        val error = result.exceptionOrNull()
        error.shouldBeInstanceOf<CatalogError.RateLimitExceeded>()
        (error as CatalogError.RateLimitExceeded).retryAfterSeconds shouldBe 30L
    }

    @Test
    fun `http client maps 500 server error to ProviderUnavailable`() = runTest {
        val client = mockOkHttpClient(statusCode = 503, responseBody = "Service Unavailable")
        val kitsuClient = KitsuHttpClient(client, json)

        val result = kitsuClient.getTrendingManga(10)
        result.isFailure shouldBe true
        result.exceptionOrNull().shouldBeInstanceOf<CatalogError.ProviderUnavailable>()
    }

    @Test
    fun `http client maps 400 bad request to HttpError`() = runTest {
        val client = mockOkHttpClient(statusCode = 400, responseBody = "Bad Request")
        val kitsuClient = KitsuHttpClient(client, json)

        val result = kitsuClient.getPopularManga(0, 10)
        result.isFailure shouldBe true
        val error = result.exceptionOrNull()
        error.shouldBeInstanceOf<CatalogError.HttpError>()
        (error as CatalogError.HttpError).statusCode shouldBe 400
    }

    @Test
    fun `http client maps malformed json on 200 to SerializationError`() = runTest {
        val client = mockOkHttpClient(statusCode = 200, responseBody = "invalid-json")
        val kitsuClient = KitsuHttpClient(client, json)

        val result = kitsuClient.getMangaDetails("1234")
        result.isFailure shouldBe true
        result.exceptionOrNull().shouldBeInstanceOf<CatalogError.SerializationError>()
    }

    @Test
    fun `http client maps network IOException to NetworkError`() = runTest {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor {
                    throw IOException("Connection timed out")
                },
            )
            .build()
        val kitsuClient = KitsuHttpClient(client, json)

        val result = kitsuClient.getMangaDetails("1234")
        result.isFailure shouldBe true
        result.exceptionOrNull().shouldBeInstanceOf<CatalogError.NetworkError>()
    }

    @Test
    fun `http client succeeds when 200 with valid fixture`() = runTest {
        val searchJson = javaClass.getResource("/kitsu/kitsu_manga_search_berserk.json")!!.readText()
        val client = mockOkHttpClient(statusCode = 200, responseBody = searchJson)
        val kitsuClient = KitsuHttpClient(client, json)

        val result = kitsuClient.searchManga("Berserk", 0, 10, "-userCount")
        result.isSuccess shouldBe true
        val response = result.getOrThrow()
        response.data.size shouldBe 1
        response.data.first().attributes.canonicalTitle shouldBe "Berserk"
    }

    private fun mockOkHttpClient(
        statusCode: Int,
        responseBody: String,
        headers: Map<String, String> = emptyMap(),
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val builder = Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(statusCode)
                        .message("HTTP $statusCode")
                        .body(responseBody.toResponseBody("application/vnd.api+json".toMediaType()))

                    headers.forEach { (name, value) ->
                        builder.header(name, value)
                    }

                    builder.build()
                },
            )
            .build()
    }
}
