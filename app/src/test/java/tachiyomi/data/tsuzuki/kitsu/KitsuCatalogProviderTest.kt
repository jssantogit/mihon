package tachiyomi.data.tsuzuki.kitsu

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.data.tsuzuki.kitsu.client.KitsuClient
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuImage
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMangaAttributes
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMangaResource
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMangaResponse
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuSingleMangaResponse
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuTitles
import tachiyomi.domain.tsuzuki.catalog.model.CatalogError
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort

class KitsuCatalogProviderTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun `search maps Kitsu resources to domain catalog items correctly`() = runTest {
        val searchJson = javaClass.getResource("/kitsu/kitsu_manga_search_berserk.json")!!.readText()
        val mockResponse = json.decodeFromString<KitsuMangaResponse>(searchJson)

        val fakeClient = FakeKitsuClient(searchResult = Result.success(mockResponse))
        val provider = KitsuCatalogProvider(fakeClient)

        val result = provider.search(
            CatalogQuery(
                query = "Berserk",
                sort = CatalogSort.POPULARITY_DESC,
                offset = 0,
                limit = 10,
            ),
        )
        result.isSuccess shouldBe true

        val page = result.getOrThrow()
        page.items.size shouldBe 1
        page.hasNextPage shouldBe false
        page.totalCount shouldBe 1

        val item = page.items.first()
        item.provider shouldBe "kitsu"
        item.providerId shouldBe "1234"
        item.title shouldBe "Berserk"
        item.status shouldBe CatalogItemStatus.ONGOING
        item.format shouldBe CatalogItemFormat.MANGA
        item.score?.value shouldBe 84.56
        item.score?.maxValue shouldBe 100.0
        item.score?.voteCount shouldBe 42000
        item.coverUrl shouldBe "https://media.kitsu.io/manga/poster_images/1234/original.jpg"
        item.bannerUrl shouldBe "https://media.kitsu.io/manga/cover_images/1234/original.jpg"
        item.titles["canonical"] shouldBe "Berserk"
        item.titles["en"] shouldBe "Berserk"
        item.titles["ja_jp"] shouldBe "ベルセルク"

        fakeClient.lastSearchQuery shouldBe "Berserk"
        fakeClient.lastSearchOffset shouldBe 0
        fakeClient.lastSearchLimit shouldBe 10
        fakeClient.lastSearchSort shouldBe "-userCount"
    }

    @Test
    fun `search maps sort options correctly`() = runTest {
        val fakeClient = FakeKitsuClient(searchResult = Result.success(KitsuMangaResponse()))
        val provider = KitsuCatalogProvider(fakeClient)

        provider.search(CatalogQuery(sort = CatalogSort.POPULARITY_DESC))
        fakeClient.lastSearchSort shouldBe "-userCount"

        provider.search(CatalogQuery(sort = CatalogSort.POPULARITY_ASC))
        fakeClient.lastSearchSort shouldBe "userCount"

        provider.search(CatalogQuery(sort = CatalogSort.RATING_DESC))
        fakeClient.lastSearchSort shouldBe "-averageRating"

        provider.search(CatalogQuery(sort = CatalogSort.RATING_ASC))
        fakeClient.lastSearchSort shouldBe "averageRating"

        provider.search(CatalogQuery(sort = CatalogSort.UPDATED_DESC))
        fakeClient.lastSearchSort shouldBe "-updatedAt"

        provider.search(CatalogQuery(sort = CatalogSort.RELEVANCE))
        fakeClient.lastSearchSort shouldBe null
    }

    @Test
    fun `trending maps Kitsu trending endpoint to CatalogPage`() = runTest {
        val trendingJson = javaClass.getResource("/kitsu/kitsu_trending_manga.json")!!.readText()
        val mockResponse = json.decodeFromString<KitsuMangaResponse>(trendingJson)

        val fakeClient = FakeKitsuClient(trendingResult = Result.success(mockResponse))
        val provider = KitsuCatalogProvider(fakeClient)

        val result = provider.getTrending(offset = 0, limit = 10)
        result.isSuccess shouldBe true

        val page = result.getOrThrow()
        page.items.size shouldBe 1
        page.hasNextPage shouldBe false
        page.totalCount shouldBe 1

        val item = page.items.first()
        item.providerId shouldBe "5678"
        item.title shouldBe "Chainsaw Man"
        item.score?.value shouldBe 83.12

        fakeClient.lastTrendingLimit shouldBe 10
    }

    @Test
    fun `popular maps Kitsu popular manga to CatalogPage`() = runTest {
        val searchJson = javaClass.getResource("/kitsu/kitsu_manga_search_berserk.json")!!.readText()
        val mockResponse = json.decodeFromString<KitsuMangaResponse>(searchJson)

        val fakeClient = FakeKitsuClient(popularResult = Result.success(mockResponse))
        val provider = KitsuCatalogProvider(fakeClient)

        val result = provider.getPopular(offset = 0, limit = 20)
        result.isSuccess shouldBe true

        val page = result.getOrThrow()
        page.items.size shouldBe 1
        page.items.first().title shouldBe "Berserk"

        fakeClient.lastPopularOffset shouldBe 0
        fakeClient.lastPopularLimit shouldBe 20
    }

    @Test
    fun `getDetails maps single resource to catalog item`() = runTest {
        val detailJson = javaClass.getResource("/kitsu/kitsu_manga_details_single.json")!!.readText()
        val mockResponse = json.decodeFromString<KitsuSingleMangaResponse>(detailJson)

        val fakeClient = FakeKitsuClient(detailResult = Result.success(mockResponse))
        val provider = KitsuCatalogProvider(fakeClient)

        val result = provider.getDetails("1234")
        result.isSuccess shouldBe true

        val item = result.getOrThrow()
        item.providerId shouldBe "1234"
        item.title shouldBe "Berserk"
        item.score?.value shouldBe 84.56

        fakeClient.lastDetailId shouldBe "1234"
    }

    @Test
    fun `search maps status options correctly`() = runTest {
        val fakeClient = FakeKitsuClient(searchResult = Result.success(KitsuMangaResponse()))
        val provider = KitsuCatalogProvider(fakeClient)

        provider.search(CatalogQuery(status = CatalogItemStatus.ONGOING))
        fakeClient.lastSearchStatus shouldBe "current"

        provider.search(CatalogQuery(status = CatalogItemStatus.COMPLETED))
        fakeClient.lastSearchStatus shouldBe "finished"

        provider.search(CatalogQuery(status = CatalogItemStatus.CANCELLED))
        fakeClient.lastSearchStatus shouldBe null

        provider.search(CatalogQuery(status = CatalogItemStatus.ON_HIATUS))
        fakeClient.lastSearchStatus shouldBe null

        provider.search(CatalogQuery(status = CatalogItemStatus.UNKNOWN))
        fakeClient.lastSearchStatus shouldBe null

        provider.search(CatalogQuery(status = null))
        fakeClient.lastSearchStatus shouldBe null
    }

    @Test
    fun `status mapping handles all required cases`() = runTest {
        val fakeClient = FakeKitsuClient()
        val provider = KitsuCatalogProvider(fakeClient)

        suspend fun itemWithStatus(status: String?): CatalogItem {
            val resource = KitsuMangaResource(
                id = "1",
                type = "manga",
                attributes = KitsuMangaAttributes(
                    canonicalTitle = "Title",
                    status = status,
                ),
            )
            fakeClient.detailResult = Result.success(KitsuSingleMangaResponse(data = resource))
            return provider.getDetails("1").getOrThrow()
        }

        itemWithStatus("current").status shouldBe CatalogItemStatus.ONGOING
        itemWithStatus("CURRENT").status shouldBe CatalogItemStatus.ONGOING
        itemWithStatus("finished").status shouldBe CatalogItemStatus.COMPLETED
        itemWithStatus("unreleased").status shouldBe CatalogItemStatus.UNKNOWN
        itemWithStatus("tba").status shouldBe CatalogItemStatus.UNKNOWN
        itemWithStatus("some_random_status").status shouldBe CatalogItemStatus.UNKNOWN
        itemWithStatus(null).status shouldBe CatalogItemStatus.UNKNOWN
    }

    @Test
    fun `format mapping handles all required subtypes`() = runTest {
        val fakeClient = FakeKitsuClient()
        val provider = KitsuCatalogProvider(fakeClient)

        suspend fun itemWithSubtype(subtype: String?): CatalogItem {
            val resource = KitsuMangaResource(
                id = "1",
                type = "manga",
                attributes = KitsuMangaAttributes(
                    canonicalTitle = "Title",
                    subtype = subtype,
                ),
            )
            fakeClient.detailResult = Result.success(KitsuSingleMangaResponse(data = resource))
            return provider.getDetails("1").getOrThrow()
        }

        itemWithSubtype("manga").format shouldBe CatalogItemFormat.MANGA
        itemWithSubtype("novel").format shouldBe CatalogItemFormat.NOVEL
        itemWithSubtype("oneshot").format shouldBe CatalogItemFormat.ONE_SHOT
        itemWithSubtype("manhwa").format shouldBe CatalogItemFormat.MANHWA
        itemWithSubtype("manhua").format shouldBe CatalogItemFormat.MANHUA
        itemWithSubtype("doujin").format shouldBe CatalogItemFormat.DOUJIN
        itemWithSubtype("unknown_format").format shouldBe CatalogItemFormat.UNKNOWN
        itemWithSubtype(null).format shouldBe CatalogItemFormat.UNKNOWN
    }

    @Test
    fun `score extraction and normalization handles valid, blank, and null ratings`() = runTest {
        val fakeClient = FakeKitsuClient()
        val provider = KitsuCatalogProvider(fakeClient)

        suspend fun itemWithRating(rating: String?, userCount: Int?): CatalogItem {
            val resource = KitsuMangaResource(
                id = "1",
                type = "manga",
                attributes = KitsuMangaAttributes(
                    canonicalTitle = "Title",
                    averageRating = rating,
                    userCount = userCount,
                ),
            )
            fakeClient.detailResult = Result.success(KitsuSingleMangaResponse(data = resource))
            return provider.getDetails("1").getOrThrow()
        }

        val validScoreItem = itemWithRating("84.51", 1200)
        validScoreItem.score shouldNotBe null
        validScoreItem.score?.provider shouldBe "kitsu"
        validScoreItem.score?.value shouldBe 84.51
        validScoreItem.score?.maxValue shouldBe 100.0
        validScoreItem.score?.voteCount shouldBe 1200

        itemWithRating(null, 100).score shouldBe null
        itemWithRating("", 100).score shouldBe null
        itemWithRating("   ", 100).score shouldBe null
        itemWithRating("not-a-number", 100).score shouldBe null
    }

    @Test
    fun `cover and banner image extraction follows fallback cascade`() = runTest {
        val fakeClient = FakeKitsuClient()
        val provider = KitsuCatalogProvider(fakeClient)

        suspend fun itemForResource(resource: KitsuMangaResource): CatalogItem {
            fakeClient.detailResult = Result.success(KitsuSingleMangaResponse(data = resource))
            return provider.getDetails(resource.id).getOrThrow()
        }

        // 1. All resolutions present: original wins
        val allImages = KitsuMangaResource(
            id = "1",
            type = "manga",
            attributes = KitsuMangaAttributes(
                canonicalTitle = "Title",
                posterImage = KitsuImage(
                    original = "poster_orig",
                    large = "poster_lg",
                    medium = "poster_med",
                    small = "poster_sm",
                ),
                coverImage = KitsuImage(original = "cover_orig", large = "cover_lg", small = "cover_sm"),
            ),
        )
        val allResult = itemForResource(allImages)
        allResult.coverUrl shouldBe "poster_orig"
        allResult.bannerUrl shouldBe "cover_orig"

        // 2. Original missing: large wins
        val noOriginal = KitsuMangaResource(
            id = "2",
            type = "manga",
            attributes = KitsuMangaAttributes(
                canonicalTitle = "Title",
                posterImage = KitsuImage(large = "poster_lg", medium = "poster_med", small = "poster_sm"),
                coverImage = KitsuImage(large = "cover_lg", small = "cover_sm"),
            ),
        )
        val noOriginalResult = itemForResource(noOriginal)
        noOriginalResult.coverUrl shouldBe "poster_lg"
        noOriginalResult.bannerUrl shouldBe "cover_lg"

        // 3. Original and large missing: medium wins for poster, small wins for cover
        val mediumPosterSmallCover = KitsuMangaResource(
            id = "3",
            type = "manga",
            attributes = KitsuMangaAttributes(
                canonicalTitle = "Title",
                posterImage = KitsuImage(medium = "poster_med", small = "poster_sm"),
                coverImage = KitsuImage(small = "cover_sm"),
            ),
        )
        val mediumResult = itemForResource(mediumPosterSmallCover)
        mediumResult.coverUrl shouldBe "poster_med"
        mediumResult.bannerUrl shouldBe "cover_sm"

        // 4. All null: URLs are null
        val noImages = KitsuMangaResource(
            id = "4",
            type = "manga",
            attributes = KitsuMangaAttributes(canonicalTitle = "Title"),
        )
        val noImagesResult = itemForResource(noImages)
        noImagesResult.coverUrl shouldBe null
        noImagesResult.bannerUrl shouldBe null
    }

    @Test
    fun `multi-title dictionary maps all available titles and canonicalTitle`() = runTest {
        val resource = KitsuMangaResource(
            id = "1",
            type = "manga",
            attributes = KitsuMangaAttributes(
                canonicalTitle = "Canonical Name",
                titles = KitsuTitles(
                    en = "English Name",
                    enJp = "Romaji Name",
                    jaJp = "Japanese Name",
                    enUs = "US Name",
                ),
            ),
        )

        val fakeClient = FakeKitsuClient(detailResult = Result.success(KitsuSingleMangaResponse(data = resource)))
        val provider = KitsuCatalogProvider(fakeClient)

        val item = provider.getDetails("1").getOrThrow()
        item.title shouldBe "Canonical Name"
        item.titles["canonical"] shouldBe "Canonical Name"
        item.titles["en"] shouldBe "English Name"
        item.titles["en_jp"] shouldBe "Romaji Name"
        item.titles["ja_jp"] shouldBe "Japanese Name"
        item.titles["en_us"] shouldBe "US Name"
    }

    @Test
    fun `error propagation handles CatalogError subtypes and wraps general exceptions`() = runTest {
        val notFoundClient = FakeKitsuClient(detailResult = Result.failure(CatalogError.ItemNotFound("9999")))
        val provider1 = KitsuCatalogProvider(notFoundClient)
        val notFoundResult = provider1.getDetails("9999")
        notFoundResult.isFailure shouldBe true
        notFoundResult.exceptionOrNull().shouldBeInstanceOf<CatalogError.ItemNotFound>()

        val rateLimitClient =
            FakeKitsuClient(searchResult = Result.failure(CatalogError.RateLimitExceeded(retryAfterSeconds = 60)))
        val provider2 = KitsuCatalogProvider(rateLimitClient)
        val rateLimitResult = provider2.search(CatalogQuery(query = "fail"))
        rateLimitResult.isFailure shouldBe true
        val rateError = rateLimitResult.exceptionOrNull()
        rateError.shouldBeInstanceOf<CatalogError.RateLimitExceeded>()
        (rateError as CatalogError.RateLimitExceeded).retryAfterSeconds shouldBe 60L

        val unexpectedClient =
            FakeKitsuClient(searchResult = Result.failure(IllegalStateException("Something crashed")))
        val provider3 = KitsuCatalogProvider(unexpectedClient)
        val unexpResult = provider3.search(CatalogQuery(query = "fail"))
        unexpResult.isFailure shouldBe true
        unexpResult.exceptionOrNull().shouldBeInstanceOf<CatalogError.ProviderUnavailable>()
    }

    private class FakeKitsuClient(
        var searchResult: Result<KitsuMangaResponse> = Result.success(KitsuMangaResponse()),
        var trendingResult: Result<KitsuMangaResponse> = Result.success(KitsuMangaResponse()),
        var popularResult: Result<KitsuMangaResponse>? = null,
        var detailResult: Result<KitsuSingleMangaResponse>? = null,
    ) : KitsuClient {
        var lastSearchQuery: String? = null
        var lastSearchOffset: Int = 0
        var lastSearchLimit: Int = 0
        var lastSearchSort: String? = null
        var lastSearchStatus: String? = null
        var lastTrendingLimit: Int = 0
        var lastPopularOffset: Int = 0
        var lastPopularLimit: Int = 0
        var lastDetailId: String? = null

        override suspend fun searchManga(
            query: String?,
            offset: Int,
            limit: Int,
            sort: String?,
            status: String?,
        ): Result<KitsuMangaResponse> {
            lastSearchQuery = query
            lastSearchOffset = offset
            lastSearchLimit = limit
            lastSearchSort = sort
            lastSearchStatus = status
            return searchResult
        }

        override suspend fun getTrendingManga(limit: Int): Result<KitsuMangaResponse> {
            lastTrendingLimit = limit
            return trendingResult
        }

        override suspend fun getPopularManga(offset: Int, limit: Int): Result<KitsuMangaResponse> {
            lastPopularOffset = offset
            lastPopularLimit = limit
            return popularResult ?: searchManga(null, offset, limit, "-userCount", null)
        }

        override suspend fun getMangaDetails(kitsuId: String): Result<KitsuSingleMangaResponse> {
            lastDetailId = kitsuId
            return detailResult ?: Result.failure(CatalogError.ItemNotFound(kitsuId))
        }

        override suspend fun getMangaById(id: String): Result<KitsuSingleMangaResponse> = getMangaDetails(id)
    }
}
