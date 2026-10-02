package tachiyomi.domain.tsuzuki.catalog.cache

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.model.CatalogRatingMatch
import tachiyomi.domain.tsuzuki.integration.model.ExternalRating

class RatingEnrichmentCacheTest {

    @Test
    fun `known exact provider identity bypasses identity resolver`() = runTest {
        val provider = CountingRatingsProvider()
        val cache = cache()

        val ids = cache.resolveExternalIds(
            item = item(externalIds = mapOf("mal" to "m1")),
            provider = provider,
            configurationFingerprint = "cfg",
        ).getOrThrow()

        ids["mal"] shouldBe "m1"
        provider.resolveCalls shouldBe 0
    }

    @Test
    fun `simultaneous rating requests share one provider call`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val provider = CountingRatingsProvider(ratingGate = gate)
        val cache = cache()
        val identified = item(externalIds = mapOf("mal" to "m1"))

        val first = async {
            cache.ratingFor(identified, provider, "cfg").getOrThrow()
        }
        val second = async {
            cache.ratingFor(identified, provider, "cfg").getOrThrow()
        }
        runCurrent()

        provider.ratingCalls shouldBe 1
        gate.complete(Unit)
        first.await()?.rating?.value shouldBe 8.4
        second.await()?.rating?.value shouldBe 8.4
        provider.ratingCalls shouldBe 1
    }

    @Test
    fun `negative rating cache expires sooner than a positive cache`() = runTest {
        var now = 0L
        val provider = CountingRatingsProvider(match = null)
        val cache = RatingEnrichmentCache(
            scope = backgroundScope,
            clock = { now },
            positiveTtlMillis = 1_000L,
            negativeTtlMillis = 100L,
            maxEntries = 16,
        )
        val identified = item(externalIds = mapOf("mal" to "m1"))

        cache.ratingFor(identified, provider, "cfg").getOrThrow() shouldBe null
        now = 99L
        cache.ratingFor(identified, provider, "cfg").getOrThrow() shouldBe null
        provider.ratingCalls shouldBe 1

        now = 101L
        cache.ratingFor(identified, provider, "cfg").getOrThrow() shouldBe null
        provider.ratingCalls shouldBe 2
    }

    @Test
    fun `configuration fingerprint isolates cached rating results`() = runTest {
        val provider = CountingRatingsProvider()
        val cache = cache()
        val identified = item(externalIds = mapOf("mal" to "m1"))

        cache.ratingFor(identified, provider, "cfg-a").getOrThrow()
        cache.ratingFor(identified, provider, "cfg-a").getOrThrow()
        provider.ratingCalls shouldBe 1

        cache.ratingFor(identified, provider, "cfg-b").getOrThrow()
        provider.ratingCalls shouldBe 2
    }

    private fun kotlinx.coroutines.test.TestScope.cache() = RatingEnrichmentCache(
        scope = backgroundScope,
        clock = { 0L },
        positiveTtlMillis = 1_000L,
        negativeTtlMillis = 100L,
        maxEntries = 16,
    )

    private fun item(externalIds: Map<String, String>) = CatalogItem(
        provider = "kitsu",
        providerId = "k1",
        title = "Work",
        externalIds = externalIds,
        startDate = "2020-01-01",
        authors = listOf("Author"),
    )

    private class CountingRatingsProvider(
        private val ratingGate: CompletableDeferred<Unit>? = null,
        private val match: CatalogRatingMatch? = CatalogRatingMatch(
            externalId = "m1",
            rating = ExternalRating(
                providerId = "mal",
                label = "MAL",
                value = 8.4,
                scaleMax = 10.0,
            ),
        ),
    ) : RatingsProvider {
        override val integrationId = IntegrationId("mal")
        var resolveCalls = 0
        var ratingCalls = 0

        override suspend fun resolveExternalIds(item: CatalogItem): Result<Map<String, String>> {
            resolveCalls++
            return Result.success(mapOf("mal" to "m1"))
        }

        override suspend fun ratings(externalId: String): Result<List<ExternalRating>> =
            Result.success(emptyList())

        override suspend fun ratingFor(item: CatalogItem): Result<CatalogRatingMatch?> {
            ratingCalls++
            ratingGate?.await()
            return Result.success(match)
        }
    }
}
