package eu.kanade.tachiyomi.data.tsuzuki.integration

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import tachiyomi.domain.tsuzuki.integration.model.IntegrationSettings
import tachiyomi.domain.tsuzuki.integration.model.TrackingUpdate
import tachiyomi.domain.tsuzuki.integration.repository.IntegrationSettingsRepository

class DefaultIntegrationRegistryTest {

    @Test
    fun `registry excludes providers whose integration is disabled`() = runTest {
        val settings = MutableStateFlow(fakeSettings("kitsu" to false))
        val registry = registry(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            settings = settings,
            searchProviders = setOf(FakeSearchProvider("kitsu")),
        )

        registry.searchProviders() shouldBe emptyList()
    }

    @Test
    fun `registry excludes providers without a persisted setting`() = runTest {
        val registry = registry(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            settings = MutableStateFlow(emptyList()),
            searchProviders = setOf(FakeSearchProvider("kitsu")),
        )

        registry.searchProviders() shouldBe emptyList()
    }

    @Test
    fun `registry does not infer enabled state from provider presence`() = runTest {
        val kitsuSearch = FakeSearchProvider("kitsu")
        val malSearch = FakeSearchProvider("mal")
        val malDiscovery = FakeDiscoveryProvider("mal")
        val registry = registry(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            settings = MutableStateFlow(fakeSettings("kitsu" to false, "mal" to true)),
            searchProviders = setOf(kitsuSearch, malSearch),
            discoveryProviders = setOf(FakeDiscoveryProvider("kitsu"), malDiscovery),
            metadataProviders = setOf(FakeMetadataProvider("kitsu"), FakeMetadataProvider("mal")),
            chapterEvidenceProviders = setOf(FakeChapterEvidenceProvider("kitsu"), FakeChapterEvidenceProvider("mal")),
            ratingsProviders = setOf(FakeRatingsProvider("kitsu"), FakeRatingsProvider("mal")),
            trackingProviders = setOf(FakeTrackingProvider("kitsu"), FakeTrackingProvider("mal")),
        )

        registry.searchProviders() shouldContainExactly listOf(malSearch)
        registry.discoveryProviders() shouldContainExactly listOf(malDiscovery)
        registry.metadataProviders().map { it.integrationId.value } shouldContainExactly listOf("mal")
        registry.chapterEvidenceProviders().map { it.producerId } shouldContainExactly listOf("mal")
        registry.ratingsProviders().map { it.integrationId.value } shouldContainExactly listOf("mal")
        registry.trackingProviders().map { it.integrationId.value } shouldContainExactly listOf("mal")
    }

    @Test
    fun `personal server metadata stays server scoped`() = runTest {
        val komga = FakeMetadataProvider("komga")
        val registry = registry(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            settings = MutableStateFlow(fakeSettings("komga" to true)),
            metadataProviders = setOf(komga),
        )

        registry.metadataProviders() shouldBe emptyList()
        registry.isGlobalCapabilityActive(
            IntegrationId("komga"),
            IntegrationCapability.METADATA_BASIC,
        ) shouldBe false
    }

    @Test
    fun `registry exposes unified integration manifests`() = runTest {
        val registry = registry(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            settings = MutableStateFlow(emptyList()),
        )

        registry.manifests().map { it.integrationId.value }.toSet() shouldBe setOf(
            "kitsu",
            "mal",
            "mangaupdates",
            "bangumi",
            "shikimori",
            "hikka",
            "komga",
            "kavita",
            "suwayomi",
        )
    }

    @Test
    fun `catalog providers with ranked feeds declare discovery capability`() = runTest {
        val registry = registry(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            settings = MutableStateFlow(emptyList()),
        )

        listOf("mangaupdates", "bangumi").forEach { integrationId ->
            val manifest = registry.manifests().single { it.integrationId.value == integrationId }

            (IntegrationCapability.DISCOVERY in manifest.capabilities) shouldBe true
        }
    }

    @Test
    fun `staff metadata is exposed only by manifests that declare it`() = runTest {
        val kitsu = FakeMetadataProvider("kitsu")
        val mal = FakeMetadataProvider("mal")
        val registry = registry(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            settings = MutableStateFlow(fakeSettings("kitsu" to true, "mal" to true)),
            metadataProviders = setOf(kitsu, mal),
        )

        registry.metadataProviders(IntegrationCapability.METADATA_STAFF)
            .map { it.integrationId.value } shouldContainExactly listOf("mal")
    }

    @Test
    fun `metadata capability switches are independent`() = runTest {
        val kitsu = FakeMetadataProvider("kitsu")
        val registry = registry(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            settings = MutableStateFlow(
                listOf(
                    IntegrationSettings(
                        integrationId = IntegrationId("kitsu"),
                        enabled = true,
                        configJson = """{"metadata":true,"metadata_artwork":false}""",
                    ),
                ),
            ),
            metadataProviders = setOf(kitsu),
        )

        registry.metadataProviders() shouldContainExactly listOf(kitsu)
        registry.metadataProviders(IntegrationCapability.METADATA_ARTWORK) shouldBe emptyList()
        registry.isGlobalCapabilityActive(
            IntegrationId("kitsu"),
            IntegrationCapability.METADATA_BASIC,
        ) shouldBe true
        registry.isGlobalCapabilityActive(
            IntegrationId("kitsu"),
            IntegrationCapability.METADATA_ARTWORK,
        ) shouldBe false
    }

    @Test
    fun `unified manifests preserve legacy tracker ids`() = runTest {
        val registry = registry(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            settings = MutableStateFlow(emptyList()),
        )

        registry.manifests().associate { it.integrationId.value to it.legacyTrackerId } shouldBe mapOf(
            "kitsu" to 3L,
            "mal" to 1L,
            "mangaupdates" to 7L,
            "bangumi" to 5L,
            "shikimori" to 4L,
            "hikka" to 10L,
            "komga" to 6L,
            "kavita" to 8L,
            "suwayomi" to 9L,
        )
    }

    @Test
    fun `registry reflects settings changes without being reconstructed`() = runTest {
        val kitsuSearch = FakeSearchProvider("kitsu")
        val settings = MutableStateFlow(fakeSettings("kitsu" to false))
        val registry = registry(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            settings = settings,
            searchProviders = setOf(kitsuSearch),
        )

        registry.searchProviders() shouldBe emptyList()

        settings.value = fakeSettings("kitsu" to true)
        registry.searchProviders() shouldContainExactly listOf(kitsuSearch)

        settings.value = fakeSettings("kitsu" to false)
        registry.searchProviders() shouldBe emptyList()
    }

    @Test
    fun `registry honors disabled capability without disabling provider entirely`() = runTest {
        val kitsuSearch = FakeSearchProvider("kitsu")
        val kitsuDiscovery = FakeDiscoveryProvider("kitsu")
        val settings = MutableStateFlow(
            listOf(
                IntegrationSettings(
                    integrationId = IntegrationId("kitsu"),
                    enabled = true,
                    configJson = """{"search":true,"discovery":false}""",
                ),
            ),
        )
        val registry = registry(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            settings = settings,
            searchProviders = setOf(kitsuSearch),
            discoveryProviders = setOf(kitsuDiscovery),
        )

        registry.searchProviders() shouldContainExactly listOf(kitsuSearch)
        registry.discoveryProviders() shouldBe emptyList()
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
