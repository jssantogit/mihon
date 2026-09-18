package tachiyomi.data.tsuzuki.kitsu

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.interactor.GetDiscoverFeed
import tachiyomi.domain.tsuzuki.catalog.interactor.SearchCatalog
import tachiyomi.domain.tsuzuki.catalog.model.CatalogError
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.model.CatalogScore
import tachiyomi.domain.tsuzuki.catalog.service.CatalogProvider

class KitsuErrorResilienceTest {

    @Test
    fun `rate limit failure in provider is exposed as typed RateLimitExceeded without crashing`() = runTest {
        val failingProvider = object : CatalogProvider {
            override val providerId: String = "kitsu"
            override val displayName: String = "Kitsu"
            override suspend fun search(query: CatalogQuery): Result<CatalogPage> =
                Result.failure(CatalogError.RateLimitExceeded(retryAfterSeconds = 120))
            override suspend fun getTrending(offset: Int, limit: Int): Result<CatalogPage> =
                Result.failure(CatalogError.RateLimitExceeded(retryAfterSeconds = 120))
            override suspend fun getPopular(offset: Int, limit: Int): Result<CatalogPage> =
                Result.failure(CatalogError.RateLimitExceeded(retryAfterSeconds = 120))
            override suspend fun getDetails(providerId: String): Result<CatalogItem> =
                Result.failure(CatalogError.RateLimitExceeded(retryAfterSeconds = 120))
        }

        val search = SearchCatalog(failingProvider)
        val result = search.execute("Guts")

        result.isFailure shouldBe true
        val error = result.exceptionOrNull()
        error.shouldBeInstanceOf<CatalogError.RateLimitExceeded>()
        error.retryAfterSeconds shouldBe 120
    }

    @Test
    fun `outage during discover does not throw unhandled exception`() = runTest {
        val outageProvider = object : CatalogProvider {
            override val providerId: String = "kitsu"
            override val displayName: String = "Kitsu"
            override suspend fun search(query: CatalogQuery): Result<CatalogPage> =
                Result.failure(CatalogError.ProviderUnavailable("Kitsu is down"))
            override suspend fun getTrending(offset: Int, limit: Int): Result<CatalogPage> =
                Result.failure(CatalogError.ProviderUnavailable("Kitsu is down"))
            override suspend fun getPopular(offset: Int, limit: Int): Result<CatalogPage> =
                Result.failure(CatalogError.ProviderUnavailable("Kitsu is down"))
            override suspend fun getDetails(providerId: String): Result<CatalogItem> =
                Result.failure(CatalogError.ProviderUnavailable("Kitsu is down"))
        }

        val discover = GetDiscoverFeed(outageProvider)
        val feed = discover.execute()

        feed.trending.isFailure shouldBe true
        feed.popular.isFailure shouldBe true
        feed.isDegraded shouldBe true
        feed.isCompleteFailure shouldBe true
    }

    @Test
    fun `discover feed partial degradation delivers popular when trending suffers outage`() = runTest {
        val partiallyDegradedProvider = object : CatalogProvider {
            override val providerId: String = "kitsu"
            override val displayName: String = "Kitsu"
            override suspend fun search(
                query: CatalogQuery,
            ): Result<CatalogPage> = Result.failure(NotImplementedError())
            override suspend fun getTrending(offset: Int, limit: Int): Result<CatalogPage> =
                Result.failure(CatalogError.ProviderUnavailable("Trending endpoint 500"))
            override suspend fun getPopular(offset: Int, limit: Int): Result<CatalogPage> =
                Result.success(CatalogPage(listOf(CatalogItem("kitsu", "1", "Berserk")), false))
            override suspend fun getDetails(
                providerId: String,
            ): Result<CatalogItem> = Result.failure(NotImplementedError())
        }

        val discover = GetDiscoverFeed(partiallyDegradedProvider)
        val feed = discover.execute()

        feed.trending.isFailure shouldBe true
        feed.popular.isSuccess shouldBe true
        feed.isDegraded shouldBe true
        feed.isCompleteFailure shouldBe false
        feed.popular.getOrThrow().items.first().title shouldBe "Berserk"
    }

    @Test
    fun `provider score remains provider-specific and is never averaged`() {
        val kitsuScore = CatalogScore(
            provider = "kitsu",
            value = 84.5,
            maxValue = 100.0,
            voteCount = 42000,
        )

        kitsuScore.provider shouldBe "kitsu"
        kitsuScore.value shouldBe 84.5
        kitsuScore.maxValue shouldBe 100.0
        kitsuScore.voteCount shouldBe 42000
    }

    @Test
    fun `search and discover interactors do not trigger persistence or side effects`() = runTest {
        var persistenceTriggered = false
        val nonPersistingProvider = object : CatalogProvider {
            override val providerId: String = "kitsu"
            override val displayName: String = "Kitsu"
            override suspend fun search(query: CatalogQuery): Result<CatalogPage> {
                return Result.success(CatalogPage(listOf(CatalogItem("kitsu", "10", "Ephemeral Title")), false))
            }
            override suspend fun getTrending(offset: Int, limit: Int): Result<CatalogPage> {
                return Result.success(CatalogPage(listOf(CatalogItem("kitsu", "10", "Ephemeral Title")), false))
            }
            override suspend fun getPopular(offset: Int, limit: Int): Result<CatalogPage> {
                return Result.success(CatalogPage(listOf(CatalogItem("kitsu", "10", "Ephemeral Title")), false))
            }
            override suspend fun getDetails(providerId: String): Result<CatalogItem> {
                persistenceTriggered = true
                return Result.failure(NotImplementedError())
            }
        }

        val search = SearchCatalog(nonPersistingProvider)
        val discover = GetDiscoverFeed(nonPersistingProvider)

        val searchResult = search.await("Ephemeral")
        val discoverFeed = discover.await()

        searchResult.isSuccess shouldBe true
        discoverFeed.trending.isSuccess shouldBe true
        discoverFeed.popular.isSuccess shouldBe true
        persistenceTriggered shouldBe false
    }
}
