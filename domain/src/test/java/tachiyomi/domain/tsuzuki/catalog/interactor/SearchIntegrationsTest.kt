package tachiyomi.domain.tsuzuki.catalog.interactor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.model.CatalogScore
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider
import tachiyomi.domain.tsuzuki.integration.model.CatalogRatingMatch
import tachiyomi.domain.tsuzuki.integration.model.ExternalRating

class SearchIntegrationsTest {

    @Test
    fun `equal titles from unrelated providers remain distinct candidates`() = runTest {
        val search = SearchIntegrations(
            registry(
                FakeSearchProvider(
                    id = "kitsu",
                    result = Result.success(page(CatalogItem("kitsu", "1", "Same"))),
                ),
                FakeSearchProvider(
                    id = "mal",
                    result = Result.success(page(CatalogItem("mal", "2", "Same"))),
                ),
            ),
        )

        val results = search.execute(CatalogQuery(query = "Same"))

        results.map { it.provider to it.providerId } shouldContainExactly listOf(
            "kitsu" to "1",
            "mal" to "2",
        )
    }

    @Test
    fun `verified cross-provider mapping collapses one work and preserves both ratings`() = runTest {
        val search = SearchIntegrations(
            registry(
                FakeSearchProvider(
                    id = "kitsu",
                    result = Result.success(
                        page(
                            CatalogItem(
                                provider = "kitsu",
                                providerId = "one-piece",
                                title = "One Piece",
                                score = CatalogScore(
                                    provider = "kitsu",
                                    value = 85.08,
                                    maxValue = 100.0,
                                ),
                                externalIds = mapOf("mal" to "13"),
                            ),
                        ),
                    ),
                ),
                FakeSearchProvider(
                    id = "mal",
                    result = Result.success(
                        page(
                            CatalogItem(
                                provider = "mal",
                                providerId = "13",
                                title = "One Piece",
                                score = CatalogScore(
                                    provider = "mal",
                                    value = 9.21,
                                    maxValue = 10.0,
                                ),
                            ),
                        ),
                    ),
                ),
                ratingProviders = listOf(
                    FakeRatingsProvider("kitsu", emptyMap()),
                    FakeRatingsProvider("mal", emptyMap()),
                ),
            ),
        )

        val results = search.execute(CatalogQuery(query = "One Piece"))

        results.size shouldBe 1
        results.single().scores.map(CatalogScore::provider) shouldContainExactly listOf("mal", "kitsu")
        results.single().scores.map(CatalogScore::value) shouldContainExactly listOf(9.21, 85.08)
    }

    @Test
    fun `catalog work is enriched with enabled provider ratings even without another catalog row`() = runTest {
        val search = SearchIntegrations(
            registry(
                FakeSearchProvider(
                    id = "kitsu",
                    result = Result.success(
                        page(
                            CatalogItem(
                                provider = "kitsu",
                                providerId = "one-piece",
                                title = "One Piece",
                                score = CatalogScore(
                                    provider = "kitsu",
                                    value = 85.08,
                                    maxValue = 100.0,
                                ),
                                externalIds = mapOf("mal" to "13"),
                            ),
                        ),
                    ),
                ),
                ratingProviders = listOf(
                    FakeRatingsProvider(
                        id = "kitsu",
                        ratingsByExternalId = emptyMap(),
                    ),
                    FakeRatingsProvider(
                        id = "mal",
                        ratingsByExternalId = mapOf(
                            "13" to ExternalRating(
                                providerId = "mal",
                                label = "MAL",
                                value = 9.21,
                                scaleMax = 10.0,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val results = search.execute(CatalogQuery(query = "One Piece"))

        results.size shouldBe 1
        results.single().scores.map(CatalogScore::provider) shouldContainExactly listOf("mal", "kitsu")
        results.single().scores.map(CatalogScore::value) shouldContainExactly listOf(9.21, 85.08)
    }

    @Test
    fun `four enabled rating providers are preserved in stable order`() = runTest {
        val item = CatalogItem(
            provider = "kitsu",
            providerId = "k1",
            title = "Four Scores",
            score = CatalogScore("kitsu", 80.0, 100.0),
            externalIds = mapOf(
                "mal" to "m1",
                "mangaupdates" to "mu1",
                "bangumi" to "b1",
            ),
        )
        val search = SearchIntegrations(
            registry(
                FakeSearchProvider("kitsu", Result.success(page(item))),
                ratingProviders = listOf(
                    FakeRatingsProvider("kitsu", emptyMap()),
                    FakeRatingsProvider(
                        "mal",
                        mapOf("m1" to ExternalRating("mal", "MAL", 8.0, 10.0)),
                    ),
                    FakeRatingsProvider(
                        "mangaupdates",
                        mapOf("mu1" to ExternalRating("mangaupdates", "MangaUpdates", 8.4, 10.0)),
                    ),
                    FakeRatingsProvider(
                        "bangumi",
                        mapOf("b1" to ExternalRating("bangumi", "Bangumi", 7.9, 10.0)),
                    ),
                ),
            ),
        )

        val result = search.execute(CatalogQuery(query = "Four Scores")).single()

        result.scores.map(CatalogScore::provider) shouldContainExactly listOf(
            "mal",
            "kitsu",
            "mangaupdates",
            "bangumi",
        )
    }

    @Test
    fun `ephemeral rating match enriches score without becoming canonical identity`() = runTest {
        val ratingProvider = object : RatingsProvider {
            override val integrationId = IntegrationId("mangaupdates")

            override suspend fun ratings(externalId: String): Result<List<ExternalRating>> =
                Result.success(emptyList())

            override suspend fun ratingFor(item: CatalogItem): Result<CatalogRatingMatch?> =
                Result.success(
                    CatalogRatingMatch(
                        externalId = "42",
                        rating = ExternalRating(
                            providerId = "mangaupdates",
                            label = "MangaUpdates",
                            value = 9.1,
                            scaleMax = 10.0,
                        ),
                        verifiedIdentity = false,
                    ),
                )
        }
        val search = SearchIntegrations(
            registry(
                FakeSearchProvider(
                    id = "kitsu",
                    result = Result.success(
                        page(
                            CatalogItem(
                                provider = "kitsu",
                                providerId = "k1",
                                title = "Work",
                                score = CatalogScore("kitsu", 81.0, 100.0),
                            ),
                        ),
                    ),
                ),
                ratingProviders = listOf(
                    FakeRatingsProvider("kitsu", emptyMap()),
                    ratingProvider,
                ),
            ),
        )

        val result = search.execute(CatalogQuery(query = "Work")).single()

        result.scores.map(CatalogScore::provider) shouldContainExactly listOf("kitsu", "mangaupdates")
        result.externalIds["mangaupdates"] shouldBe null
    }

    @Test
    fun `same provider and title with distinct external identities remain distinct`() = runTest {
        val search = SearchIntegrations(
            registry(
                FakeSearchProvider(
                    id = "kitsu",
                    result = Result.success(
                        page(
                            CatalogItem("kitsu", "42", "Tokyo Ghoul"),
                            CatalogItem("kitsu", "43", "Tokyo Ghoul"),
                        ),
                    ),
                ),
            ),
        )

        val results = search.execute(CatalogQuery(query = "Tokyo Ghoul"))

        results.map { it.provider to it.providerId } shouldContainExactly listOf(
            "kitsu" to "42",
            "kitsu" to "43",
        )
    }

    @Test
    fun `duplicate rows with the same external identity collapse safely`() = runTest {
        val search = SearchIntegrations(
            registry(
                FakeSearchProvider(
                    id = "kitsu",
                    result = Result.success(
                        page(
                            CatalogItem("kitsu", "42", "Tokyo Ghoul"),
                            CatalogItem("kitsu", "42", "Tokyo Ghoul"),
                        ),
                    ),
                ),
            ),
        )

        val results = search.execute(CatalogQuery(query = "Tokyo Ghoul"))

        results.map { it.provider to it.providerId } shouldContainExactly listOf("kitsu" to "42")
    }

    @Test
    fun `one provider failure does not erase successful provider results`() = runTest {
        val search = SearchIntegrations(
            registry(
                FakeSearchProvider(
                    id = "kitsu",
                    result = Result.failure(IllegalStateException("Kitsu unavailable")),
                ),
                FakeSearchProvider(
                    id = "mal",
                    result = Result.success(page(CatalogItem("mal", "10", "Monster"))),
                ),
            ),
        )

        val results = search.execute(CatalogQuery(query = "Monster"))

        results.map { it.providerId } shouldContainExactly listOf("10")
    }

    @Test
    fun `enabled providers are queried concurrently`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val first = object : SearchProvider {
            override val integrationId = IntegrationId("first")

            override suspend fun search(query: CatalogQuery): Result<CatalogPage> {
                firstStarted.complete(Unit)
                secondStarted.await()
                return Result.success(page(CatalogItem("first", "1", "One")))
            }
        }
        val second = object : SearchProvider {
            override val integrationId = IntegrationId("second")

            override suspend fun search(query: CatalogQuery): Result<CatalogPage> {
                secondStarted.complete(Unit)
                firstStarted.await()
                return Result.success(page(CatalogItem("second", "2", "Two")))
            }
        }
        val search = SearchIntegrations(registry(first, second))

        val results = withTimeout(1_000) {
            search.execute(CatalogQuery(query = "query"))
        }

        results.size shouldBe 2
    }

    @Test
    fun `caller cancellation is never swallowed as a provider failure`() = runTest {
        val cancelling = object : SearchProvider {
            override val integrationId = IntegrationId("cancel")

            override suspend fun search(query: CatalogQuery): Result<CatalogPage> {
                throw CancellationException("cancelled")
            }
        }
        val search = SearchIntegrations(registry(cancelling))

        shouldThrow<CancellationException> {
            search.execute(CatalogQuery(query = "query"))
        }
    }

    private fun page(vararg items: CatalogItem) = CatalogPage(
        items = items.toList(),
        hasNextPage = false,
    )

    private fun registry(
        vararg providers: SearchProvider,
        ratingProviders: List<RatingsProvider> = emptyList(),
    ) = object : IntegrationRegistry {
        override fun searchProviders(): List<SearchProvider> = providers.toList()
        override fun discoveryProviders(): List<DiscoveryProvider> = emptyList()
        override fun metadataProviders(): List<MetadataProvider> = emptyList()
        override fun chapterEvidenceProviders(): List<ChapterEvidenceProvider> = emptyList()
        override fun ratingsProviders(): List<RatingsProvider> = ratingProviders
        override fun trackingProviders(): List<TrackingProvider> = emptyList()
    }

    private class FakeRatingsProvider(
        id: String,
        private val ratingsByExternalId: Map<String, ExternalRating>,
    ) : RatingsProvider {
        override val integrationId = IntegrationId(id)

        override suspend fun ratings(externalId: String): Result<List<ExternalRating>> =
            Result.success(listOfNotNull(ratingsByExternalId[externalId]))
    }

    private class FakeSearchProvider(
        id: String,
        private val result: Result<CatalogPage>,
    ) : SearchProvider {
        override val integrationId = IntegrationId(id)

        override suspend fun search(query: CatalogQuery): Result<CatalogPage> = result
    }
}
