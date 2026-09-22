package eu.kanade.tachiyomi.ui.tsuzuki.search

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.tsuzuki.catalog.interactor.SearchIntegrations
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitle
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitleFromCatalog
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

@OptIn(ExperimentalCoroutinesApi::class)
class TsuzukiSearchScreenModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial discover waits for registry readiness and loads without a prior search`() = runTest(dispatcher) {
        val registry = DelayedRegistry()
        val model = TsuzukiSearchScreenModel(
            searchIntegrations = SearchIntegrations(registry),
            registry = registry,
            searchPreferences = TsuzukiSearchPreferences(InMemoryPreferenceStore()),
            materializeCanonicalTitleFromCatalog = MaterializeCanonicalTitleFromCatalog(
                MaterializeCanonicalTitle(FakeCanonicalTitleRepository()),
                mockk(relaxed = true),
            ),
        )

        dispatcher.scheduler.runCurrent()
        model.state.value.shouldBeInstanceOf<SearchState.Loading>()

        registry.release()
        advanceUntilIdle()

        val discover = model.state.value.shouldBeInstanceOf<SearchState.Discover>()
        discover.blocks.map(DiscoverBlock::kind) shouldBe listOf(
            DiscoverKind.TRENDING,
            DiscoverKind.POPULAR,
            DiscoverKind.RECENTLY_UPDATED,
        )
    }

    @Test
    fun `search requests integration setup when no search provider is enabled`() = runTest(dispatcher) {
        val registry = emptyRegistry()
        val model = TsuzukiSearchScreenModel(
            searchIntegrations = SearchIntegrations(registry),
            registry = registry,
            searchPreferences = TsuzukiSearchPreferences(InMemoryPreferenceStore()),
            materializeCanonicalTitleFromCatalog = MaterializeCanonicalTitleFromCatalog(
                MaterializeCanonicalTitle(FakeCanonicalTitleRepository()),
                mockk(relaxed = true),
            ),
        )
        advanceUntilIdle()

        model.search("Dandadan")
        advanceUntilIdle()

        model.state.value.shouldBeInstanceOf<SearchState.NeedsIntegration>()
    }

    private class FakeCanonicalTitleRepository : CanonicalTitleRepository {
        override suspend fun getById(id: String): CanonicalTitle? = null

        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> =
            MutableStateFlow(null)

        override suspend fun getByExternalIdentity(
            provider: String,
            externalId: String,
        ): CanonicalTitle? = null

        override suspend fun getOrCreateByExternalIdentity(
            title: CanonicalTitle,
            identity: ExternalIdentity,
        ): CanonicalTitle = title

        override suspend fun insert(title: CanonicalTitle) = Unit

        override suspend fun addExternalIdentity(identity: ExternalIdentity) = Unit
    }

    private class DelayedRegistry : IntegrationRegistry {
        private val ready = CompletableDeferred<Unit>()

        private val discovery = object : DiscoveryProvider {
            override val integrationId = IntegrationId("kitsu")

            override suspend fun trending(offset: Int, limit: Int): Result<CatalogPage> =
                Result.success(CatalogPage(emptyList(), hasNextPage = false))

            override suspend fun popular(offset: Int, limit: Int): Result<CatalogPage> =
                Result.success(CatalogPage(emptyList(), hasNextPage = false))

            override suspend fun recentlyUpdated(offset: Int, limit: Int): Result<CatalogPage> =
                Result.success(CatalogPage(emptyList(), hasNextPage = false))
        }

        fun release() {
            ready.complete(Unit)
        }

        override suspend fun awaitReady() {
            ready.await()
        }

        override fun searchProviders(): List<SearchProvider> = emptyList()
        override fun discoveryProviders(): List<DiscoveryProvider> = listOf(discovery)
        override fun metadataProviders(): List<MetadataProvider> = emptyList()
        override fun chapterEvidenceProviders(): List<ChapterEvidenceProvider> = emptyList()
        override fun ratingsProviders(): List<RatingsProvider> = emptyList()
        override fun trackingProviders(): List<TrackingProvider> = emptyList()
    }

    private fun emptyRegistry() = object : IntegrationRegistry {
        override fun searchProviders(): List<SearchProvider> = emptyList()
        override fun discoveryProviders(): List<DiscoveryProvider> = emptyList()
        override fun metadataProviders(): List<MetadataProvider> = emptyList()
        override fun chapterEvidenceProviders(): List<ChapterEvidenceProvider> = emptyList()
        override fun ratingsProviders(): List<RatingsProvider> = emptyList()
        override fun trackingProviders(): List<TrackingProvider> = emptyList()
    }
}
