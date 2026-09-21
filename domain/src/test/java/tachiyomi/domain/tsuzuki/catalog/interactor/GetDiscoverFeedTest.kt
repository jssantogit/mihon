package tachiyomi.domain.tsuzuki.catalog.interactor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogError
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider

class GetDiscoverFeedTest {

    @Test
    fun `discover feed returns trending and popular sections in parallel`() = runTest {
        val fakeProvider = FakeDiscoveryProvider(
            trendingResult = Result.success(CatalogPage(listOf(CatalogItem("kitsu", "1", "Trending 1")), false)),
            popularResult = Result.success(CatalogPage(listOf(CatalogItem("kitsu", "2", "Popular 1")), false)),
        )
        val interactor = GetDiscoverFeed(registry(discoveryProviders = listOf(fakeProvider)))

        val feed = interactor.await(trendingLimit = 10, popularLimit = 20)

        feed.trending.isSuccess shouldBe true
        feed.popular.isSuccess shouldBe true
        feed.isDegraded shouldBe false
        feed.isCompleteFailure shouldBe false
        feed.trending.getOrThrow().items.first().title shouldBe "Trending 1"
        feed.popular.getOrThrow().items.first().title shouldBe "Popular 1"
        fakeProvider.lastTrendingLimit shouldBe 10
        fakeProvider.lastPopularLimit shouldBe 20
    }

    @Test
    fun `discover feed returns typed failures when no discovery integration is enabled`() = runTest {
        val interactor = GetDiscoverFeed(registry())

        val feed = interactor.await()

        feed.trending.isFailure shouldBe true
        feed.popular.isFailure shouldBe true
        feed.trending.exceptionOrNull().shouldBeInstanceOf<CatalogError.ProviderUnavailable>()
        feed.popular.exceptionOrNull().shouldBeInstanceOf<CatalogError.ProviderUnavailable>()
        feed.isCompleteFailure shouldBe true
    }

    @Test
    fun `discover feed isolates failures between sections when trending fails`() = runTest {
        val fakeProvider = FakeDiscoveryProvider(
            trendingResult = Result.failure(CatalogError.NetworkError(Exception("Timeout"))),
            popularResult = Result.success(CatalogPage(listOf(CatalogItem("kitsu", "2", "Popular 1")), false)),
        )
        val interactor = GetDiscoverFeed(registry(discoveryProviders = listOf(fakeProvider)))

        val feed = interactor.await()

        feed.trending.isFailure shouldBe true
        feed.popular.isSuccess shouldBe true
        feed.isDegraded shouldBe true
        feed.isCompleteFailure shouldBe false
        feed.popular.getOrThrow().items.first().title shouldBe "Popular 1"
    }

    @Test
    fun `discover feed isolates failures between sections when popular fails`() = runTest {
        val fakeProvider = FakeDiscoveryProvider(
            trendingResult = Result.success(CatalogPage(listOf(CatalogItem("kitsu", "1", "Trending 1")), false)),
            popularResult = Result.failure(CatalogError.ProviderUnavailable("Popular backend down")),
        )
        val interactor = GetDiscoverFeed(registry(discoveryProviders = listOf(fakeProvider)))

        val feed = interactor()

        feed.trending.isSuccess shouldBe true
        feed.popular.isFailure shouldBe true
        feed.isDegraded shouldBe true
        feed.isCompleteFailure shouldBe false
        feed.trending.getOrThrow().items.first().title shouldBe "Trending 1"
    }

    @Test
    fun `discover feed reports complete failure when both sections fail`() = runTest {
        val fakeProvider = FakeDiscoveryProvider(
            trendingResult = Result.failure(CatalogError.ProviderUnavailable("Trending down")),
            popularResult = Result.failure(CatalogError.ProviderUnavailable("Popular down")),
        )
        val interactor = GetDiscoverFeed(registry(discoveryProviders = listOf(fakeProvider)))

        val feed = interactor.execute()

        feed.trending.isFailure shouldBe true
        feed.popular.isFailure shouldBe true
        feed.isDegraded shouldBe true
        feed.isCompleteFailure shouldBe true
    }

    @Test
    fun `discover feed isolates unhandled exceptions thrown by provider`() = runTest {
        val provider = object : DiscoveryProvider {
            override val integrationId = IntegrationId("kitsu")

            override suspend fun trending(offset: Int, limit: Int): Result<CatalogPage> =
                throw RuntimeException("Trending crashed")

            override suspend fun popular(offset: Int, limit: Int): Result<CatalogPage> =
                Result.success(CatalogPage(listOf(CatalogItem("kitsu", "2", "Popular Survives")), false))

            override suspend fun recentlyUpdated(offset: Int, limit: Int): Result<CatalogPage> =
                Result.success(CatalogPage(emptyList(), false))
        }
        val interactor = GetDiscoverFeed(registry(discoveryProviders = listOf(provider)))

        val feed = interactor.await()

        feed.trending.isFailure shouldBe true
        feed.trending.exceptionOrNull()?.message shouldBe "Trending crashed"
        feed.popular.isSuccess shouldBe true
        feed.popular.getOrThrow().items.first().title shouldBe "Popular Survives"
        feed.isDegraded shouldBe true
    }

    @Test
    fun `discover feed propagates cancellation when caller is cancelled`() = runTest {
        val provider = object : DiscoveryProvider {
            override val integrationId = IntegrationId("kitsu")

            override suspend fun trending(offset: Int, limit: Int): Result<CatalogPage> =
                throw CancellationException("Scope cancelled")

            override suspend fun popular(offset: Int, limit: Int): Result<CatalogPage> =
                Result.success(CatalogPage(listOf(CatalogItem("kitsu", "2", "Popular 1")), false))

            override suspend fun recentlyUpdated(offset: Int, limit: Int): Result<CatalogPage> =
                Result.success(CatalogPage(emptyList(), false))
        }
        val interactor = GetDiscoverFeed(registry(discoveryProviders = listOf(provider)))

        shouldThrow<CancellationException> {
            interactor.await()
        }
    }

    private fun registry(
        discoveryProviders: List<DiscoveryProvider> = emptyList(),
    ) = object : IntegrationRegistry {
        override fun searchProviders(): List<SearchProvider> = emptyList()
        override fun discoveryProviders(): List<DiscoveryProvider> = discoveryProviders
        override fun metadataProviders(): List<MetadataProvider> = emptyList()
        override fun chapterEvidenceProviders(): List<ChapterEvidenceProvider> = emptyList()
        override fun ratingsProviders(): List<RatingsProvider> = emptyList()
        override fun trackingProviders(): List<TrackingProvider> = emptyList()
    }

    private class FakeDiscoveryProvider(
        private val trendingResult: Result<CatalogPage>,
        private val popularResult: Result<CatalogPage>,
    ) : DiscoveryProvider {
        override val integrationId = IntegrationId("kitsu")
        var lastTrendingLimit: Int = 0
        var lastPopularLimit: Int = 0

        override suspend fun trending(offset: Int, limit: Int): Result<CatalogPage> {
            lastTrendingLimit = limit
            return trendingResult
        }

        override suspend fun popular(offset: Int, limit: Int): Result<CatalogPage> {
            lastPopularLimit = limit
            return popularResult
        }

        override suspend fun recentlyUpdated(offset: Int, limit: Int): Result<CatalogPage> =
            Result.success(CatalogPage(emptyList(), false))
    }
}
