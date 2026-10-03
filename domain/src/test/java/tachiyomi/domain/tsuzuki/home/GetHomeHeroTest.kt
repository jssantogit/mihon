package tachiyomi.domain.tsuzuki.home

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.home.interactor.GetHomeHero
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider

class GetHomeHeroTest {

    @Test
    fun `hero prefers the first trending item with a banner and never loads popular`() = runTest {
        val coverOnly = CatalogItem(
            provider = "kitsu",
            providerId = "1",
            title = "Cover only",
            coverUrl = "https://example/cover.jpg",
        )
        val banner = CatalogItem(
            provider = "kitsu",
            providerId = "2",
            title = "Banner",
            coverUrl = "https://example/banner-cover.jpg",
            bannerUrl = "https://example/banner.jpg",
        )
        val provider = FakeDiscoveryProvider(
            Result.success(CatalogPage(listOf(coverOnly, banner), hasNextPage = false)),
        )
        val interactor = GetHomeHero(registry(provider))

        val hero = interactor.await(limit = 6)

        hero shouldBe banner
        provider.trendingCalls shouldBe 1
        provider.lastTrendingLimit shouldBe 6
        provider.popularCalls shouldBe 0
    }

    @Test
    fun `hero falls back to the first trending item with cover artwork when banners are absent`() = runTest {
        val noArtwork = CatalogItem(
            provider = "kitsu",
            providerId = "0",
            title = "No artwork",
        )
        val coverOnly = CatalogItem(
            provider = "kitsu",
            providerId = "1",
            title = "Cover only",
            coverUrl = "https://example/cover.jpg",
        )
        val provider = FakeDiscoveryProvider(
            Result.success(CatalogPage(listOf(noArtwork, coverOnly), hasNextPage = false)),
        )
        val interactor = GetHomeHero(registry(provider))

        interactor.await() shouldBe coverOnly
    }

    @Test
    fun `hero is absent when no discovery provider is enabled`() = runTest {
        GetHomeHero(registry()).await() shouldBe null
    }

    @Test
    fun `hero is absent when trending fails`() = runTest {
        val provider = FakeDiscoveryProvider(
            Result.failure(IllegalStateException("offline")),
        )

        GetHomeHero(registry(provider)).await() shouldBe null
    }

    private fun registry(
        provider: DiscoveryProvider? = null,
    ) = object : IntegrationRegistry {
        override fun searchProviders(): List<SearchProvider> = emptyList()
        override fun discoveryProviders(): List<DiscoveryProvider> = listOfNotNull(provider)
        override fun metadataProviders(): List<MetadataProvider> = emptyList()
        override fun chapterEvidenceProviders(): List<ChapterEvidenceProvider> = emptyList()
        override fun ratingsProviders(): List<RatingsProvider> = emptyList()
        override fun trackingProviders(): List<TrackingProvider> = emptyList()
    }

    private class FakeDiscoveryProvider(
        private val trendingResult: Result<CatalogPage>,
    ) : DiscoveryProvider {
        override val integrationId = IntegrationId("kitsu")
        var trendingCalls = 0
        var popularCalls = 0
        var lastTrendingLimit = 0

        override suspend fun trending(offset: Int, limit: Int): Result<CatalogPage> {
            trendingCalls += 1
            lastTrendingLimit = limit
            return trendingResult
        }

        override suspend fun popular(offset: Int, limit: Int): Result<CatalogPage> {
            popularCalls += 1
            return Result.success(CatalogPage(emptyList(), hasNextPage = false))
        }

        override suspend fun recentlyUpdated(offset: Int, limit: Int): Result<CatalogPage> =
            Result.success(CatalogPage(emptyList(), hasNextPage = false))
    }
}
