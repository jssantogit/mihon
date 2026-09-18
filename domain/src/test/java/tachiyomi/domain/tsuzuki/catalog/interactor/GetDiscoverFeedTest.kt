package tachiyomi.domain.tsuzuki.catalog.interactor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogError
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.service.CatalogProvider

class GetDiscoverFeedTest {

    @Test
    fun `discover feed returns trending and popular sections in parallel`() = runTest {
        val fakeProvider = FakeFeedProvider(
            trendingResult = Result.success(CatalogPage(listOf(CatalogItem("kitsu", "1", "Trending 1")), false)),
            popularResult = Result.success(CatalogPage(listOf(CatalogItem("kitsu", "2", "Popular 1")), false)),
        )
        val interactor = GetDiscoverFeed(fakeProvider)

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
    fun `discover feed isolates failures between sections when trending fails`() = runTest {
        val fakeProvider = FakeFeedProvider(
            trendingResult = Result.failure(CatalogError.NetworkError(Exception("Timeout"))),
            popularResult = Result.success(CatalogPage(listOf(CatalogItem("kitsu", "2", "Popular 1")), false)),
        )
        val interactor = GetDiscoverFeed(fakeProvider)

        val feed = interactor.await()
        feed.trending.isFailure shouldBe true
        feed.popular.isSuccess shouldBe true
        feed.isDegraded shouldBe true
        feed.isCompleteFailure shouldBe false
        feed.popular.getOrThrow().items.first().title shouldBe "Popular 1"
    }

    @Test
    fun `discover feed isolates failures between sections when popular fails`() = runTest {
        val fakeProvider = FakeFeedProvider(
            trendingResult = Result.success(CatalogPage(listOf(CatalogItem("kitsu", "1", "Trending 1")), false)),
            popularResult = Result.failure(CatalogError.ProviderUnavailable("Popular backend down")),
        )
        val interactor = GetDiscoverFeed(fakeProvider)

        val feed = interactor()
        feed.trending.isSuccess shouldBe true
        feed.popular.isFailure shouldBe true
        feed.isDegraded shouldBe true
        feed.isCompleteFailure shouldBe false
        feed.trending.getOrThrow().items.first().title shouldBe "Trending 1"
    }

    @Test
    fun `discover feed reports complete failure when both sections fail`() = runTest {
        val fakeProvider = FakeFeedProvider(
            trendingResult = Result.failure(CatalogError.ProviderUnavailable("Trending down")),
            popularResult = Result.failure(CatalogError.ProviderUnavailable("Popular down")),
        )
        val interactor = GetDiscoverFeed(fakeProvider)

        val feed = interactor.execute()
        feed.trending.isFailure shouldBe true
        feed.popular.isFailure shouldBe true
        feed.isDegraded shouldBe true
        feed.isCompleteFailure shouldBe true
    }

    @Test
    fun `discover feed isolates unhandled exceptions thrown by provider`() = runTest {
        val failingTrendingProvider = object : CatalogProvider {
            override val providerId: String = "kitsu"
            override val displayName: String = "Kitsu"
            override suspend fun search(
                query: CatalogQuery,
            ): Result<CatalogPage> = Result.failure(NotImplementedError())
            override suspend fun getTrending(
                offset: Int,
                limit: Int,
            ): Result<CatalogPage> = throw RuntimeException("Trending crashed")
            override suspend fun getPopular(offset: Int, limit: Int): Result<CatalogPage> =
                Result.success(CatalogPage(listOf(CatalogItem("kitsu", "2", "Popular Survives")), false))
            override suspend fun getDetails(
                providerId: String,
            ): Result<CatalogItem> = Result.failure(NotImplementedError())
        }
        val interactor = GetDiscoverFeed(failingTrendingProvider)

        val feed = interactor.await()
        feed.trending.isFailure shouldBe true
        feed.trending.exceptionOrNull()?.message shouldBe "Trending crashed"
        feed.popular.isSuccess shouldBe true
        feed.popular.getOrThrow().items.first().title shouldBe "Popular Survives"
        feed.isDegraded shouldBe true
    }

    @Test
    fun `discover feed propagates cancellation when caller is cancelled`() = runTest {
        val cancellingProvider = object : CatalogProvider {
            override val providerId: String = "kitsu"
            override val displayName: String = "Kitsu"
            override suspend fun search(
                query: CatalogQuery,
            ): Result<CatalogPage> = Result.failure(NotImplementedError())
            override suspend fun getTrending(
                offset: Int,
                limit: Int,
            ): Result<CatalogPage> = throw CancellationException("Scope cancelled")
            override suspend fun getPopular(offset: Int, limit: Int): Result<CatalogPage> =
                Result.success(CatalogPage(listOf(CatalogItem("kitsu", "2", "Popular 1")), false))
            override suspend fun getDetails(
                providerId: String,
            ): Result<CatalogItem> = Result.failure(NotImplementedError())
        }
        val interactor = GetDiscoverFeed(cancellingProvider)

        shouldThrow<CancellationException> {
            interactor.await()
        }
    }

    private class FakeFeedProvider(
        val trendingResult: Result<CatalogPage>,
        val popularResult: Result<CatalogPage>,
    ) : CatalogProvider {
        override val providerId: String = "kitsu"
        override val displayName: String = "Kitsu"
        var lastTrendingLimit: Int = 0
        var lastPopularLimit: Int = 0

        override suspend fun search(query: CatalogQuery): Result<CatalogPage> = popularResult
        override suspend fun getTrending(offset: Int, limit: Int): Result<CatalogPage> {
            lastTrendingLimit = limit
            return trendingResult
        }
        override suspend fun getPopular(offset: Int, limit: Int): Result<CatalogPage> {
            lastPopularLimit = limit
            return popularResult
        }
        override suspend fun getDetails(providerId: String): Result<CatalogItem> = Result.failure(NotImplementedError())
    }
}
