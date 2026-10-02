package tachiyomi.domain.tsuzuki.catalog.interactor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.cache.RatingEnrichmentCache
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
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import tachiyomi.domain.tsuzuki.integration.model.RatingIdentityEvidence

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
        result.tsuzukiRating?.sourceCount shouldBe 4
        result.tsuzukiRating?.value shouldBe 8.075
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
        result.scores.last().identityEvidence shouldBe RatingIdentityEvidence.CORROBORATED_RATING_ONLY
        result.tsuzukiRating?.sourceCount shouldBe 2
        result.tsuzukiRating?.verifiedSourceCount shouldBe 1
        result.tsuzukiRating?.corroboratedSourceCount shouldBe 1
        result.externalIds["mangaupdates"] shouldBe null
    }

    @Test
    fun `disabling Tsuzuki ratings hides only the aggregate`() = runTest {
        val item = CatalogItem(
            provider = "kitsu",
            providerId = "k1",
            title = "Work",
            score = CatalogScore("kitsu", 80.0, 100.0),
            externalIds = mapOf("mal" to "m1"),
        )
        val search = SearchIntegrations(
            registry(
                FakeSearchProvider("kitsu", Result.success(page(item))),
                ratingProviders = listOf(
                    FakeRatingsProvider("kitsu", emptyMap()),
                    FakeRatingsProvider(
                        "mal",
                        mapOf("m1" to ExternalRating("mal", "MAL", 8.4, 10.0)),
                    ),
                ),
                tsuzukiRatingsEnabled = false,
            ),
        )

        val result = search.execute(CatalogQuery(query = "Work")).single()

        result.scores.map(CatalogScore::provider) shouldContainExactly listOf("mal", "kitsu")
        result.tsuzukiRating shouldBe null
    }

    @Test
    fun `rating enrichment reuses cached provider result across repeated pages`() = runTest {
        val provider = object : RatingsProvider {
            override val integrationId = IntegrationId("mal")
            var ratingCalls = 0

            override suspend fun ratings(externalId: String): Result<List<ExternalRating>> =
                Result.success(emptyList())

            override suspend fun ratingFor(item: CatalogItem): Result<CatalogRatingMatch?> {
                ratingCalls++
                return Result.success(
                    CatalogRatingMatch(
                        externalId = "m1",
                        rating = ExternalRating(
                            providerId = "mal",
                            label = "MAL",
                            value = 8.4,
                            scaleMax = 10.0,
                        ),
                    ),
                )
            }
        }
        val item = CatalogItem(
            provider = "kitsu",
            providerId = "k1",
            title = "Work",
            externalIds = mapOf("mal" to "m1"),
        )
        val search = SearchIntegrations(
            registry(
                FakeSearchProvider("kitsu", Result.success(page(item))),
                ratingProviders = listOf(provider),
            ),
        )

        search.enrichRatings(listOf(item)).single().scores.single().value shouldBe 8.4
        search.enrichRatings(listOf(item)).single().scores.single().value shouldBe 8.4

        provider.ratingCalls shouldBe 1
    }

    @Test
    fun `repeated base search reuses the completed provider result`() = runTest {
        var calls = 0
        val provider = object : SearchProvider {
            override val integrationId = IntegrationId("kitsu")

            override suspend fun search(query: CatalogQuery): Result<CatalogPage> {
                calls++
                return Result.success(page(CatalogItem("kitsu", "1", "Work")))
            }
        }
        val search = SearchIntegrations(registry(provider))
        val query = CatalogQuery(query = "Work")

        search.executeBase(query).map(CatalogItem::providerId) shouldContainExactly listOf("1")
        search.executeBase(query).map(CatalogItem::providerId) shouldContainExactly listOf("1")

        calls shouldBe 1
    }

    @Test
    fun `partial base search failure is not cached as a complete query result`() = runTest {
        var healthyCalls = 0
        var flakyCalls = 0
        val healthy = object : SearchProvider {
            override val integrationId = IntegrationId("healthy")

            override suspend fun search(query: CatalogQuery): Result<CatalogPage> {
                healthyCalls++
                return Result.success(page(CatalogItem("healthy", "1", "Healthy")))
            }
        }
        val flaky = object : SearchProvider {
            override val integrationId = IntegrationId("flaky")

            override suspend fun search(query: CatalogQuery): Result<CatalogPage> {
                flakyCalls++
                return if (flakyCalls == 1) {
                    Result.failure(IllegalStateException("temporary"))
                } else {
                    Result.success(page(CatalogItem("flaky", "2", "Recovered")))
                }
            }
        }
        val search = SearchIntegrations(registry(healthy, flaky))
        val query = CatalogQuery(query = "Work")

        search.executeBase(query).map(CatalogItem::providerId) shouldContainExactly listOf("1")
        search.executeBase(query).map(CatalogItem::providerId) shouldContainExactly listOf("1", "2")

        healthyCalls shouldBe 2
        flakyCalls shouldBe 2
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
    fun `base search publishes a fast provider before a slow sibling finishes`() = runTest {
        val slowRelease = CompletableDeferred<Unit>()
        val fast = object : SearchProvider {
            override val integrationId = IntegrationId("fast")

            override suspend fun search(query: CatalogQuery): Result<CatalogPage> =
                Result.success(page(CatalogItem("fast", "1", "Fast")))
        }
        val slow = object : SearchProvider {
            override val integrationId = IntegrationId("slow")

            override suspend fun search(query: CatalogQuery): Result<CatalogPage> {
                slowRelease.await()
                return Result.success(page(CatalogItem("slow", "2", "Slow")))
            }
        }
        val search = SearchIntegrations(registry(fast, slow))
        val published = mutableListOf<List<String>>()

        val operation = async {
            search.executeBaseProgressively(CatalogQuery(query = "work")) { items ->
                published += items.map(CatalogItem::providerId)
            }
        }
        runCurrent()

        published.last() shouldContainExactly listOf("1")
        operation.isCompleted shouldBe false

        slowRelease.complete(Unit)

        operation.await().map(CatalogItem::providerId) shouldContainExactly listOf("1", "2")
        published.last() shouldContainExactly listOf("1", "2")
    }

    @Test
    fun `rating enrichment fills a freed item slot without waiting for a slow sibling`() = runTest {
        val slowRelease = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val provider = object : RatingsProvider {
            override val integrationId = IntegrationId("mal")

            override suspend fun resolveExternalIds(item: CatalogItem): Result<Map<String, String>> {
                started += item.providerId
                if (item.providerId == "1") {
                    slowRelease.await()
                }
                return Result.success(emptyMap())
            }

            override suspend fun ratings(externalId: String): Result<List<ExternalRating>> =
                Result.success(emptyList())
        }
        val search = SearchIntegrations(
            registry(
                FakeSearchProvider("fake", Result.success(page())),
                ratingProviders = listOf(provider),
            ),
            ratingEnrichmentCache = RatingEnrichmentCache(
                scope = this,
                clock = { 0L },
                positiveTtlMillis = 60_000L,
                negativeTtlMillis = 60_000L,
                maxEntries = 32,
            ),
        )
        val items = (1..6).map { index ->
            CatalogItem(
                provider = "fake",
                providerId = index.toString(),
                title = "Work $index",
            )
        }

        val operation = async {
            search.enrichRatingsProgressively(items) { _, _ -> }
        }
        runCurrent()

        started shouldContainExactly listOf("1", "2", "3", "4", "5", "6")
        operation.isCompleted shouldBe false

        slowRelease.complete(Unit)
        operation.await()
    }

    @Test
    fun `progressive rating enrichment prioritizes a bounded first item window`() = runTest {
        val release = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val provider = object : RatingsProvider {
            override val integrationId = IntegrationId("mal")

            override suspend fun resolveExternalIds(item: CatalogItem): Result<Map<String, String>> {
                started += item.providerId
                release.await()
                return Result.success(emptyMap())
            }

            override suspend fun ratings(externalId: String): Result<List<ExternalRating>> =
                Result.success(emptyList())
        }
        val cache = RatingEnrichmentCache(
            scope = this,
            clock = { 0L },
            positiveTtlMillis = 60_000L,
            negativeTtlMillis = 60_000L,
            maxEntries = 32,
        )
        val search = SearchIntegrations(
            registry(
                FakeSearchProvider("fake", Result.success(page())),
                ratingProviders = listOf(provider),
            ),
            ratingEnrichmentCache = cache,
        )
        val items = (1..6).map { index ->
            CatalogItem(
                provider = "fake",
                providerId = index.toString(),
                title = "Work $index",
            )
        }

        val operation = async {
            search.enrichRatingsProgressively(items) { _, _ -> }
        }
        runCurrent()

        started shouldContainExactly listOf("1", "2", "3")

        release.complete(Unit)
        operation.await().map(CatalogItem::providerId) shouldContainExactly
            listOf("1", "2", "3", "4", "5", "6")
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
        tsuzukiRatingsEnabled: Boolean = true,
    ) = object : IntegrationRegistry {
        override fun configurationFingerprint(): String = "cfg"
        override fun searchProviders(): List<SearchProvider> = providers.toList()
        override fun discoveryProviders(): List<DiscoveryProvider> = emptyList()
        override fun metadataProviders(): List<MetadataProvider> = emptyList()
        override fun isGlobalCapabilityActive(
            integrationId: IntegrationId,
            capability: IntegrationCapability,
        ): Boolean = integrationId.value == "tsuzuki" &&
            capability == IntegrationCapability.RATINGS &&
            tsuzukiRatingsEnabled
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
