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
    fun `hero carousel returns four artwork candidates preferring banners and never loads popular`() = runTest {
        val coverOne = item("1", coverUrl = "https://example/cover-1.jpg")
        val bannerOne = item(
            "2",
            coverUrl = "https://example/cover-2.jpg",
            bannerUrl = "https://example/banner-2.jpg",
        )
        val bannerTwo = item(
            "3",
            coverUrl = "https://example/cover-3.jpg",
            bannerUrl = "https://example/banner-3.jpg",
        )
        val coverTwo = item("4", coverUrl = "https://example/cover-4.jpg")
        val bannerThree = item(
            "5",
            coverUrl = "https://example/cover-5.jpg",
            bannerUrl = "https://example/banner-5.jpg",
        )
        val provider = FakeDiscoveryProvider(
            Result.success(
                CatalogPage(
                    listOf(coverOne, bannerOne, bannerTwo, coverTwo, bannerThree),
                    hasNextPage = false,
                ),
            ),
        )
        val interactor = GetHomeHero(registry(provider))

        val heroes = interactor.await(limit = 6, count = 4)

        heroes.map(CatalogItem::providerId) shouldBe listOf("2", "3", "5", "1")
        provider.trendingCalls shouldBe 1
        provider.lastTrendingLimit shouldBe 6
        provider.popularCalls shouldBe 0
    }

    @Test
    fun `hero carousel fills remaining slots with cover artwork when banners are scarce`() = runTest {
        val noArtwork = item("0")
        val coverOne = item("1", coverUrl = "https://example/cover-1.jpg")
        val banner = item(
            "2",
            coverUrl = "https://example/cover-2.jpg",
            bannerUrl = "https://example/banner-2.jpg",
        )
        val coverTwo = item("3", coverUrl = "https://example/cover-3.jpg")
        val provider = FakeDiscoveryProvider(
            Result.success(
                CatalogPage(
                    listOf(noArtwork, coverOne, banner, coverTwo),
                    hasNextPage = false,
                ),
            ),
        )
        val interactor = GetHomeHero(registry(provider))

        interactor.await(count = 4).map(CatalogItem::providerId) shouldBe listOf("2", "1", "3")
    }

    @Test
    fun `hero carousel is empty when no discovery provider is enabled`() = runTest {
        GetHomeHero(registry()).await() shouldBe emptyList()
    }

    @Test
    fun `hero carousel is empty when trending fails`() = runTest {
        val provider = FakeDiscoveryProvider(
            Result.failure(IllegalStateException("offline")),
        )

        GetHomeHero(registry(provider)).await() shouldBe emptyList()
    }

    private fun item(
        id: String,
        coverUrl: String? = null,
        bannerUrl: String? = null,
    ) = CatalogItem(
        provider = "kitsu",
        providerId = id,
        title = "Work $id",
        coverUrl = coverUrl,
        bannerUrl = bannerUrl,
    )

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
