package tachiyomi.domain.tsuzuki.catalog.interactor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogError
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider

class SearchCatalogTest {

    @Test
    fun `search delegates to enabled integration and returns page on success`() = runTest {
        val fakeProvider = FakeSearchProvider(
            searchResult = Result.success(
                CatalogPage(items = listOf(CatalogItem("kitsu", "1", "Monster")), hasNextPage = false),
            ),
        )
        val interactor = SearchCatalog(registry(searchProviders = listOf(fakeProvider)))

        val result = interactor.await("Monster")

        result.isSuccess shouldBe true
        val page = result.getOrThrow()
        page.items.size shouldBe 1
        page.items.first().title shouldBe "Monster"
        fakeProvider.lastQuery?.query shouldBe "Monster"
        fakeProvider.lastQuery?.sort shouldBe CatalogSort.POPULARITY_DESC
        fakeProvider.lastQuery?.offset shouldBe 0
        fakeProvider.lastQuery?.limit shouldBe 20
    }

    @Test
    fun `search returns typed failure when no search integration is enabled`() = runTest {
        val interactor = SearchCatalog(registry())

        val result = interactor.await("Monster")

        result.isFailure shouldBe true
        result.exceptionOrNull().shouldBeInstanceOf<CatalogError.ProviderUnavailable>()
    }

    @Test
    fun `search forwards query parameters sort and pagination correctly`() = runTest {
        val fakeProvider = FakeSearchProvider()
        val interactor = SearchCatalog(registry(searchProviders = listOf(fakeProvider)))

        val result = interactor(
            query = "Berserk",
            sort = CatalogSort.RATING_DESC,
            offset = 40,
            limit = 10,
            genres = listOf("Action"),
            status = CatalogItemStatus.COMPLETED,
        )

        result.isSuccess shouldBe true
        val query = fakeProvider.lastQuery!!
        query.query shouldBe "Berserk"
        query.sort shouldBe CatalogSort.RATING_DESC
        query.offset shouldBe 40
        query.limit shouldBe 10
        query.genres shouldBe listOf("Action")
        query.status shouldBe CatalogItemStatus.COMPLETED
    }

    @Test
    fun `search execute forwards parameters matching await`() = runTest {
        val fakeProvider = FakeSearchProvider(
            searchResult = Result.success(
                CatalogPage(items = listOf(CatalogItem("kitsu", "2", "Vinland Saga")), hasNextPage = false),
            ),
        )
        val interactor = SearchCatalog(registry(searchProviders = listOf(fakeProvider)))

        val result = interactor.execute("Vinland Saga", offset = 10, limit = 15, sort = CatalogSort.UPDATED_DESC)

        result.isSuccess shouldBe true
        result.getOrThrow().items.first().title shouldBe "Vinland Saga"
        fakeProvider.lastQuery?.query shouldBe "Vinland Saga"
        fakeProvider.lastQuery?.offset shouldBe 10
        fakeProvider.lastQuery?.limit shouldBe 15
        fakeProvider.lastQuery?.sort shouldBe CatalogSort.UPDATED_DESC
    }

    @Test
    fun `search filters unrelated fuzzy provider candidates for nonsense query`() = runTest {
        val providerCandidates = listOf(
            CatalogItem("kitsu", "1", "Nijiiro Days"),
            CatalogItem("kitsu", "2", "Aishiteruze Baby"),
            CatalogItem("kitsu", "3", "Saiyuuki Gaiden"),
            CatalogItem("kitsu", "4", "14R"),
            CatalogItem("kitsu", "5", "I'm Deleting Them From My Life"),
            CatalogItem("kitsu", "6", "Watashi no Otto wa Reitouko ni Nemutte Iru"),
        )
        val fakeProvider = FakeSearchProvider(
            searchResult = Result.success(
                CatalogPage(
                    items = providerCandidates,
                    hasNextPage = false,
                    totalCount = providerCandidates.size,
                ),
            ),
        )
        val interactor = SearchCatalog(registry(searchProviders = listOf(fakeProvider)))

        val result = interactor.await("vataku")

        result.isSuccess shouldBe true
        val page = result.getOrThrow()
        page.items shouldBe emptyList()
        page.hasNextPage shouldBe false
        page.totalCount shouldBe null
    }

    @Test
    fun `search keeps provider aliases that match the requested title`() = runTest {
        val fakeProvider = FakeSearchProvider(
            searchResult = Result.success(
                CatalogPage(
                    items = listOf(
                        CatalogItem(
                            provider = "kitsu",
                            providerId = "1",
                            title = "Boku no Hero Academia",
                            titles = mapOf("en" to "My Hero Academia"),
                        ),
                        CatalogItem("kitsu", "2", "Tokyo Ghoul"),
                    ),
                    hasNextPage = false,
                ),
            ),
        )
        val interactor = SearchCatalog(registry(searchProviders = listOf(fakeProvider)))

        val result = interactor.await("my hero academia").getOrThrow()

        result.items.map { it.providerId } shouldBe listOf("1")
    }

    @Test
    fun `search tolerates a small title typo without keeping unrelated candidates`() = runTest {
        val fakeProvider = FakeSearchProvider(
            searchResult = Result.success(
                CatalogPage(
                    items = listOf(
                        CatalogItem("kitsu", "1", "One Piece"),
                        CatalogItem("kitsu", "2", "Hoshi no Samidare"),
                        CatalogItem("kitsu", "3", "Dororo"),
                    ),
                    hasNextPage = false,
                ),
            ),
        )
        val interactor = SearchCatalog(registry(searchProviders = listOf(fakeProvider)))

        val result = interactor.await("one pice").getOrThrow()

        result.items.map { it.title } shouldBe listOf("One Piece")
    }

    @Test
    fun `search normalizes accents before title relevance filtering`() = runTest {
        val fakeProvider = FakeSearchProvider(
            searchResult = Result.success(
                CatalogPage(
                    items = listOf(
                        CatalogItem("kitsu", "1", "Pokémon Adventures"),
                        CatalogItem("kitsu", "2", "Monster"),
                    ),
                    hasNextPage = false,
                ),
            ),
        )
        val interactor = SearchCatalog(registry(searchProviders = listOf(fakeProvider)))

        val result = interactor.await("pokemon").getOrThrow()

        result.items.map { it.title } shouldBe listOf("Pokémon Adventures")
    }

    @Test
    fun `search returns typed failure gracefully when provider fails with RateLimitExceeded`() = runTest {
        val fakeProvider = FakeSearchProvider(
            searchResult = Result.failure(CatalogError.RateLimitExceeded(retryAfterSeconds = 30)),
        )
        val interactor = SearchCatalog(registry(searchProviders = listOf(fakeProvider)))

        val result = interactor.await("Monster")

        result.isFailure shouldBe true
        val error = result.exceptionOrNull()
        error.shouldBeInstanceOf<CatalogError.RateLimitExceeded>()
        error.retryAfterSeconds shouldBe 30
    }

    @Test
    fun `search returns typed failure gracefully when provider fails with ProviderUnavailable`() = runTest {
        val fakeProvider = FakeSearchProvider(
            searchResult = Result.failure(CatalogError.ProviderUnavailable("Kitsu unavailable")),
        )
        val interactor = SearchCatalog(registry(searchProviders = listOf(fakeProvider)))

        val result = interactor.await("Monster")

        result.isFailure shouldBe true
        result.exceptionOrNull().shouldBeInstanceOf<CatalogError.ProviderUnavailable>()
    }

    @Test
    fun `search catches unexpected Throwable and returns failure`() = runTest {
        val crashingProvider = object : SearchProvider {
            override val integrationId = IntegrationId("kitsu")

            override suspend fun search(query: CatalogQuery): Result<CatalogPage> =
                throw RuntimeException("Boom")
        }
        val interactor = SearchCatalog(registry(searchProviders = listOf(crashingProvider)))

        val result = interactor.await("Monster")

        result.isFailure shouldBe true
        result.exceptionOrNull()?.message shouldBe "Boom"
    }

    @Test
    fun `search rethrows CancellationException to preserve coroutine cancellation`() = runTest {
        val cancellingProvider = object : SearchProvider {
            override val integrationId = IntegrationId("kitsu")

            override suspend fun search(query: CatalogQuery): Result<CatalogPage> =
                throw CancellationException("Scope cancelled")
        }
        val interactor = SearchCatalog(registry(searchProviders = listOf(cancellingProvider)))

        shouldThrow<CancellationException> {
            interactor.await("Monster")
        }
    }

    private fun registry(
        searchProviders: List<SearchProvider> = emptyList(),
    ) = object : IntegrationRegistry {
        override fun searchProviders(): List<SearchProvider> = searchProviders
        override fun discoveryProviders(): List<DiscoveryProvider> = emptyList()
        override fun metadataProviders(): List<MetadataProvider> = emptyList()
        override fun chapterEvidenceProviders(): List<ChapterEvidenceProvider> = emptyList()
        override fun ratingsProviders(): List<RatingsProvider> = emptyList()
        override fun trackingProviders(): List<TrackingProvider> = emptyList()
    }

    private class FakeSearchProvider(
        var searchResult: Result<CatalogPage> = Result.success(CatalogPage(emptyList(), false)),
    ) : SearchProvider {
        override val integrationId = IntegrationId("kitsu")
        var lastQuery: CatalogQuery? = null

        override suspend fun search(query: CatalogQuery): Result<CatalogPage> {
            lastQuery = query
            return searchResult
        }
    }
}
