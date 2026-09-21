package eu.kanade.tachiyomi.data.tsuzuki.integration

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider
import tachiyomi.domain.tsuzuki.integration.model.ExternalRating
import tachiyomi.domain.tsuzuki.integration.model.IntegrationSettings
import tachiyomi.domain.tsuzuki.integration.model.TrackingUpdate

class DefaultIntegrationRegistryTest {

    @Test
    fun `registry excludes providers whose integration is disabled`() {
        val registry = DefaultIntegrationRegistry(
            settings = fakeSettings("kitsu" to false),
            searchProviders = listOf(FakeSearchProvider("kitsu")),
            discoveryProviders = emptyList(),
            metadataProviders = emptyList(),
            chapterEvidenceProviders = emptyList(),
            ratingsProviders = emptyList(),
        )

        registry.searchProviders() shouldBe emptyList()
    }

    @Test
    fun `registry excludes providers without a persisted setting`() {
        val registry = DefaultIntegrationRegistry(
            settings = emptyList(),
            searchProviders = listOf(FakeSearchProvider("kitsu")),
            discoveryProviders = emptyList(),
            metadataProviders = emptyList(),
            chapterEvidenceProviders = emptyList(),
            ratingsProviders = emptyList(),
        )

        registry.searchProviders() shouldBe emptyList()
    }

    @Test
    fun `registry does not infer enabled state from provider presence`() {
        val kitsuSearch = FakeSearchProvider("kitsu")
        val malSearch = FakeSearchProvider("mal")
        val registry = DefaultIntegrationRegistry(
            settings = fakeSettings("kitsu" to false, "mal" to true),
            searchProviders = listOf(kitsuSearch, malSearch),
            discoveryProviders = listOf(FakeDiscoveryProvider("kitsu"), FakeDiscoveryProvider("mal")),
            metadataProviders = listOf(FakeMetadataProvider("kitsu"), FakeMetadataProvider("mal")),
            chapterEvidenceProviders = listOf(FakeChapterEvidenceProvider("kitsu"), FakeChapterEvidenceProvider("mal")),
            ratingsProviders = listOf(FakeRatingsProvider("kitsu"), FakeRatingsProvider("mal")),
            trackingProviders = listOf(FakeTrackingProvider("kitsu"), FakeTrackingProvider("mal")),
        )

        registry.searchProviders() shouldContainExactly listOf(malSearch)
        registry.discoveryProviders().map { it.integrationId.value } shouldContainExactly listOf("mal")
        registry.metadataProviders().map { it.integrationId.value } shouldContainExactly listOf("mal")
        registry.chapterEvidenceProviders().map { it.producerId } shouldContainExactly listOf("mal")
        registry.ratingsProviders().map { it.integrationId.value } shouldContainExactly listOf("mal")
        registry.trackingProviders().map { it.integrationId.value } shouldContainExactly listOf("mal")
    }

    @Test
    fun `registry uses the latest setting when duplicate snapshots are supplied`() {
        val registry = DefaultIntegrationRegistry(
            settings = listOf(
                IntegrationSettings(integrationId = IntegrationId("kitsu"), enabled = true, updatedAt = 1L),
                IntegrationSettings(integrationId = IntegrationId("kitsu"), enabled = false, updatedAt = 2L),
            ),
            searchProviders = listOf(FakeSearchProvider("kitsu")),
            discoveryProviders = emptyList(),
            metadataProviders = emptyList(),
            chapterEvidenceProviders = emptyList(),
            ratingsProviders = emptyList(),
        )

        registry.searchProviders() shouldBe emptyList()
    }

    private fun fakeSettings(vararg settings: Pair<String, Boolean>): List<IntegrationSettings> =
        settings.map { (id, enabled) ->
            IntegrationSettings(integrationId = IntegrationId(id), enabled = enabled)
        }

    private class FakeSearchProvider(id: String) : SearchProvider {
        override val integrationId = IntegrationId(id)

        override suspend fun search(query: CatalogQuery): Result<CatalogPage> =
            Result.success(CatalogPage(emptyList(), hasNextPage = false))
    }

    private class FakeDiscoveryProvider(id: String) : DiscoveryProvider {
        override val integrationId = IntegrationId(id)

        override suspend fun trending(offset: Int, limit: Int): Result<CatalogPage> = emptyPage()

        override suspend fun popular(offset: Int, limit: Int): Result<CatalogPage> = emptyPage()

        override suspend fun recentlyUpdated(offset: Int, limit: Int): Result<CatalogPage> = emptyPage()

        private fun emptyPage() = Result.success(CatalogPage(emptyList(), hasNextPage = false))
    }

    private class FakeMetadataProvider(id: String) : MetadataProvider {
        override val integrationId = IntegrationId(id)

        override suspend fun getDetails(externalId: String): Result<CatalogItem> =
            Result.failure(UnsupportedOperationException())
    }

    private class FakeChapterEvidenceProvider(override val producerId: String) : ChapterEvidenceProvider {
        override suspend fun evidenceFor(canonicalTitleId: String): Result<List<ChapterEvidence>> =
            Result.success(emptyList())
    }

    private class FakeRatingsProvider(id: String) : RatingsProvider {
        override val integrationId = IntegrationId(id)

        override suspend fun ratings(externalId: String): Result<List<ExternalRating>> =
            Result.success(emptyList())
    }

    private class FakeTrackingProvider(id: String) : TrackingProvider {
        override val integrationId = IntegrationId(id)

        override suspend fun isConnected(): Boolean = false

        override suspend fun update(update: TrackingUpdate): Result<Unit> = Result.success(Unit)
    }
}
