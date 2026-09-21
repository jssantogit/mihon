package eu.kanade.tachiyomi.data.tsuzuki.integration

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
import tachiyomi.domain.tsuzuki.integration.repository.IntegrationSettingsRepository

class DefaultIntegrationRegistryTest {

    @Test
    fun `registry excludes providers whose integration is disabled`() = runTest {
        val settings = MutableStateFlow(fakeSettings("kitsu" to false))
        val registry = registry(
            scope = backgroundScope,
            settings = settings,
            searchProviders = setOf(FakeSearchProvider("kitsu")),
        )

        advanceUntilIdle()

        registry.searchProviders() shouldBe emptyList()
    }

    @Test
    fun `registry excludes providers without a persisted setting`() = runTest {
        val registry = registry(
            scope = backgroundScope,
            settings = MutableStateFlow(emptyList()),
            searchProviders = setOf(FakeSearchProvider("kitsu")),
        )

        advanceUntilIdle()

        registry.searchProviders() shouldBe emptyList()
    }

    @Test
    fun `registry does not infer enabled state from provider presence`() = runTest {
        val kitsuSearch = FakeSearchProvider("kitsu")
        val malSearch = FakeSearchProvider("mal")
        val registry = registry(
            scope = backgroundScope,
            settings = MutableStateFlow(fakeSettings("kitsu" to false, "mal" to true)),
            searchProviders = setOf(kitsuSearch, malSearch),
            discoveryProviders = setOf(FakeDiscoveryProvider("kitsu"), FakeDiscoveryProvider("mal")),
            metadataProviders = setOf(FakeMetadataProvider("kitsu"), FakeMetadataProvider("mal")),
            chapterEvidenceProviders = setOf(FakeChapterEvidenceProvider("kitsu"), FakeChapterEvidenceProvider("mal")),
            ratingsProviders = setOf(FakeRatingsProvider("kitsu"), FakeRatingsProvider("mal")),
            trackingProviders = setOf(FakeTrackingProvider("kitsu"), FakeTrackingProvider("mal")),
        )

        advanceUntilIdle()

        registry.searchProviders() shouldContainExactly listOf(malSearch)
        registry.discoveryProviders().map { it.integrationId.value } shouldContainExactly listOf("mal")
        registry.metadataProviders().map { it.integrationId.value } shouldContainExactly listOf("mal")
        registry.chapterEvidenceProviders().map { it.producerId } shouldContainExactly listOf("mal")
        registry.ratingsProviders().map { it.integrationId.value } shouldContainExactly listOf("mal")
        registry.trackingProviders().map { it.integrationId.value } shouldContainExactly listOf("mal")
    }

    @Test
    fun `registry reflects settings changes without being reconstructed`() = runTest {
        val kitsuSearch = FakeSearchProvider("kitsu")
        val settings = MutableStateFlow(fakeSettings("kitsu" to false))
        val registry = registry(
            scope = backgroundScope,
            settings = settings,
            searchProviders = setOf(kitsuSearch),
        )

        advanceUntilIdle()
        registry.searchProviders() shouldBe emptyList()

        settings.value = fakeSettings("kitsu" to true)
        advanceUntilIdle()
        registry.searchProviders() shouldContainExactly listOf(kitsuSearch)

        settings.value = fakeSettings("kitsu" to false)
        advanceUntilIdle()
        registry.searchProviders() shouldBe emptyList()
    }

    private fun registry(
        scope: CoroutineScope,
        settings: MutableStateFlow<List<IntegrationSettings>>,
        searchProviders: Set<SearchProvider> = emptySet(),
        discoveryProviders: Set<DiscoveryProvider> = emptySet(),
        metadataProviders: Set<MetadataProvider> = emptySet(),
        chapterEvidenceProviders: Set<ChapterEvidenceProvider> = emptySet(),
        ratingsProviders: Set<RatingsProvider> = emptySet(),
        trackingProviders: Set<TrackingProvider> = emptySet(),
    ) = DefaultIntegrationRegistry(
        settingsRepository = FakeIntegrationSettingsRepository(settings),
        searchProviders = searchProviders,
        discoveryProviders = discoveryProviders,
        metadataProviders = metadataProviders,
        chapterEvidenceProviders = chapterEvidenceProviders,
        ratingsProviders = ratingsProviders,
        trackingProviders = trackingProviders,
        scope = scope,
    )

    private fun fakeSettings(vararg settings: Pair<String, Boolean>): List<IntegrationSettings> =
        settings.map { (id, enabled) ->
            IntegrationSettings(integrationId = IntegrationId(id), enabled = enabled)
        }

    private class FakeIntegrationSettingsRepository(
        private val settings: MutableStateFlow<List<IntegrationSettings>>,
    ) : IntegrationSettingsRepository {
        override suspend fun get(id: IntegrationId): IntegrationSettings? =
            settings.value.firstOrNull { it.integrationId == id }

        override fun observeAll(): Flow<List<IntegrationSettings>> = settings

        override suspend fun upsert(settings: IntegrationSettings) {
            this.settings.value = this.settings.value
                .filterNot { it.integrationId == settings.integrationId } + settings
        }
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
