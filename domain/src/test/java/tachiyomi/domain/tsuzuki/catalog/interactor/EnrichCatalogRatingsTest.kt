package tachiyomi.domain.tsuzuki.catalog.interactor

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogScore
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider
import tachiyomi.domain.tsuzuki.integration.model.ExternalRating

// Regression contract: ratings are work-level enrichment, not catalog ownership.
class EnrichCatalogRatingsTest {

    @Test
    fun `mapped active rating provider enriches a catalog item even when it is absent from that provider catalog`() = runTest {
        val mal = object : RatingsProvider {
            override val integrationId = IntegrationId("mal")

            override suspend fun ratings(externalId: String): Result<List<ExternalRating>> =
                Result.success(
                    listOf(
                        ExternalRating(
                            providerId = "mal",
                            label = "MAL",
                            value = 9.21,
                            scaleMax = 10.0,
                        ),
                    ),
                )
        }
        val item = CatalogItem(
            provider = "kitsu",
            providerId = "12",
            title = "One Piece",
            score = CatalogScore(
                provider = "kitsu",
                value = 85.08,
                maxValue = 100.0,
            ),
            externalIds = mapOf("mal" to "13"),
        )

        val enriched = EnrichCatalogRatings(registry(mal))
            .execute(listOf(item))
            .single()

        enriched.scores.map(CatalogScore::provider) shouldContainExactly listOf("kitsu", "mal")
        enriched.scores.map(CatalogScore::value) shouldContainExactly listOf(85.08, 9.21)
    }

    @Test
    fun `rating provider failure preserves the catalog item own rating`() = runTest {
        val failing = object : RatingsProvider {
            override val integrationId = IntegrationId("mal")

            override suspend fun ratings(externalId: String): Result<List<ExternalRating>> =
                Result.failure(IllegalStateException("offline"))
        }
        val item = CatalogItem(
            provider = "kitsu",
            providerId = "12",
            title = "One Piece",
            score = CatalogScore(
                provider = "kitsu",
                value = 85.08,
                maxValue = 100.0,
            ),
            externalIds = mapOf("mal" to "13"),
        )

        val enriched = EnrichCatalogRatings(registry(failing))
            .execute(listOf(item))
            .single()

        enriched.scores shouldBe listOf(item.score)
    }

    private fun registry(vararg ratings: RatingsProvider) = object : IntegrationRegistry {
        override fun searchProviders(): List<SearchProvider> = emptyList()
        override fun discoveryProviders(): List<DiscoveryProvider> = emptyList()
        override fun metadataProviders(): List<MetadataProvider> = emptyList()
        override fun chapterEvidenceProviders(): List<ChapterEvidenceProvider> = emptyList()
        override fun ratingsProviders(): List<RatingsProvider> = ratings.toList()
        override fun trackingProviders(): List<TrackingProvider> = emptyList()
    }
}
