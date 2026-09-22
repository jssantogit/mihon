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
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider

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

    private fun registry(vararg providers: SearchProvider) = object : IntegrationRegistry {
        override fun searchProviders(): List<SearchProvider> = providers.toList()
        override fun discoveryProviders(): List<DiscoveryProvider> = emptyList()
        override fun metadataProviders(): List<MetadataProvider> = emptyList()
        override fun chapterEvidenceProviders(): List<ChapterEvidenceProvider> = emptyList()
        override fun ratingsProviders(): List<RatingsProvider> = emptyList()
        override fun trackingProviders(): List<TrackingProvider> = emptyList()
    }

    private class FakeSearchProvider(
        id: String,
        private val result: Result<CatalogPage>,
    ) : SearchProvider {
        override val integrationId = IntegrationId(id)

        override suspend fun search(query: CatalogQuery): Result<CatalogPage> = result
    }
}
