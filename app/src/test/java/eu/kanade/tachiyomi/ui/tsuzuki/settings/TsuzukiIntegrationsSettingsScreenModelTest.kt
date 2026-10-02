package eu.kanade.tachiyomi.ui.tsuzuki.settings

import eu.kanade.tachiyomi.data.tsuzuki.integration.DefaultIntegrationManifests
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCategory
import tachiyomi.domain.tsuzuki.integration.model.IntegrationManifest
import tachiyomi.domain.tsuzuki.integration.model.IntegrationSettings
import tachiyomi.domain.tsuzuki.integration.repository.IntegrationSettingsRepository

@OptIn(ExperimentalCoroutinesApi::class)
class TsuzukiIntegrationsSettingsScreenModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val registry = FakeIntegrationRegistry(DefaultIntegrationManifests.all)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `all unified integrations are disabled by default when no settings rows exist`() = runTest(dispatcher) {
        val repository = FakeIntegrationSettingsRepository()
        val model = TsuzukiIntegrationsSettingsScreenModel(repository, registry)

        advanceUntilIdle()

        val state = model.state.value
            .shouldBeInstanceOf<TsuzukiIntegrationsSettingsState.Loaded>()
        state.items.map { it.id.value } shouldContainExactly listOf(
            "tsuzuki",
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
        state.items.all { !it.enabled } shouldBe true
    }

    @Test
    fun `Tsuzuki is a first-party General integration with only ratings configurable`() = runTest(dispatcher) {
        val repository = FakeIntegrationSettingsRepository()
        val model = TsuzukiIntegrationsSettingsScreenModel(repository, registry)
        advanceUntilIdle()

        val item = model.state.value
            .shouldBeInstanceOf<TsuzukiIntegrationsSettingsState.Loaded>()
            .items
            .first()

        item.id.value shouldBe "tsuzuki"
        item.label shouldBe "Tsuzuki"
        item.category shouldBe IntegrationCategory.GENERAL
        item.configurableCapabilities shouldBe setOf(IntegrationCapability.RATINGS)
        item.capabilityEnabled[IntegrationCapability.RATINGS] shouldBe true
        item.supportsTracking shouldBe false
    }

    @Test
    fun `Tsuzuki global and ratings switches persist independently`() = runTest(dispatcher) {
        val repository = FakeIntegrationSettingsRepository()
        val model = TsuzukiIntegrationsSettingsScreenModel(repository, registry)
        advanceUntilIdle()

        model.setEnabled(IntegrationId("tsuzuki"), true)
        model.setCapabilityEnabled(
            IntegrationId("tsuzuki"),
            IntegrationCapability.RATINGS,
            false,
        )
        advanceUntilIdle()

        val persisted = repository.get(IntegrationId("tsuzuki"))
        persisted?.enabled shouldBe true
        val item = model.state.value
            .shouldBeInstanceOf<TsuzukiIntegrationsSettingsState.Loaded>()
            .items
            .single { it.id.value == "tsuzuki" }
        item.enabled shouldBe true
        item.capabilityEnabled[IntegrationCapability.RATINGS] shouldBe false
    }

    @Test
    fun `enabling integration persists an explicit settings row`() = runTest(dispatcher) {
        val repository = FakeIntegrationSettingsRepository()
        val model = TsuzukiIntegrationsSettingsScreenModel(repository, registry)
        advanceUntilIdle()

        model.setEnabled(IntegrationId("mal"), true)
        advanceUntilIdle()

        repository.get(IntegrationId("mal"))?.enabled shouldBe true
        model.state.value
            .shouldBeInstanceOf<TsuzukiIntegrationsSettingsState.Loaded>()
            .items
            .single { it.id.value == "mal" }
            .enabled shouldBe true
    }

    @Test
    fun `capability configuration is persisted and reflected in state`() = runTest(dispatcher) {
        val repository = FakeIntegrationSettingsRepository()
        val model = TsuzukiIntegrationsSettingsScreenModel(repository, registry)
        advanceUntilIdle()

        model.setEnabled(IntegrationId("kitsu"), true)
        model.setCapabilityEnabled(
            IntegrationId("kitsu"),
            IntegrationCapability.DISCOVERY,
            false,
        )
        advanceUntilIdle()

        val item = model.state.value
            .shouldBeInstanceOf<TsuzukiIntegrationsSettingsState.Loaded>()
            .items
            .single { it.id.value == "kitsu" }
        item.enabled shouldBe true
        item.capabilityEnabled[IntegrationCapability.DISCOVERY] shouldBe false
        item.capabilityEnabled[IntegrationCapability.SEARCH] shouldBe true
    }

    @Test
    fun `staff metadata is configurable only for providers that expose it`() = runTest(dispatcher) {
        val model = TsuzukiIntegrationsSettingsScreenModel(FakeIntegrationSettingsRepository(), registry)
        advanceUntilIdle()

        val items = model.state.value
            .shouldBeInstanceOf<TsuzukiIntegrationsSettingsState.Loaded>()
            .items

        val mal = items.single { it.id.value == "mal" }
        val mangaUpdates = items.single { it.id.value == "mangaupdates" }
        val kitsu = items.single { it.id.value == "kitsu" }

        (IntegrationCapability.METADATA_STAFF in mal.capabilities) shouldBe true
        (IntegrationCapability.METADATA_STAFF in mal.configurableCapabilities) shouldBe true
        (IntegrationCapability.METADATA_STAFF in mangaUpdates.configurableCapabilities) shouldBe true
        (IntegrationCapability.METADATA_STAFF in kitsu.capabilities) shouldBe false
    }

    @Test
    fun `hikka and shikimori expose allowed public catalog capabilities`() = runTest(dispatcher) {
        val model = TsuzukiIntegrationsSettingsScreenModel(FakeIntegrationSettingsRepository(), registry)
        advanceUntilIdle()

        val items = model.state.value
            .shouldBeInstanceOf<TsuzukiIntegrationsSettingsState.Loaded>()
            .items

        listOf("shikimori", "hikka").forEach { integrationId ->
            val item = items.single { it.id.value == integrationId }

            item.supportsTracking shouldBe true
            item.configurableCapabilities shouldBe setOf(
                IntegrationCapability.SEARCH,
                IntegrationCapability.METADATA_BASIC,
                IntegrationCapability.METADATA_ARTWORK,
                IntegrationCapability.RATINGS,
            )
            item.capabilities shouldContainExactly listOf(
                IntegrationCapability.SEARCH,
                IntegrationCapability.METADATA_BASIC,
                IntegrationCapability.METADATA_ARTWORK,
                IntegrationCapability.RATINGS,
            )
            item.restrictedCapabilities shouldBe emptyMap()
        }
    }

    @Test
    fun `personal server capabilities stay configurable within server scope`() = runTest(dispatcher) {
        val model = TsuzukiIntegrationsSettingsScreenModel(FakeIntegrationSettingsRepository(), registry)
        advanceUntilIdle()

        val item = model.state.value
            .shouldBeInstanceOf<TsuzukiIntegrationsSettingsState.Loaded>()
            .items
            .single { it.id.value == "komga" }

        (IntegrationCapability.METADATA_BASIC in item.capabilities) shouldBe true
        (IntegrationCapability.REMOTE_LIBRARY in item.capabilities) shouldBe true
        (IntegrationCapability.METADATA_BASIC in item.configurableCapabilities) shouldBe false
        item.supportsTracking shouldBe true
    }

    private class FakeIntegrationSettingsRepository : IntegrationSettingsRepository {
        private val state = MutableStateFlow<List<IntegrationSettings>>(emptyList())

        override suspend fun get(id: IntegrationId): IntegrationSettings? =
            state.value
                .filter { it.integrationId == id }
                .maxByOrNull(IntegrationSettings::updatedAt)

        override fun observeAll(): Flow<List<IntegrationSettings>> = state

        override suspend fun upsert(settings: IntegrationSettings) {
            state.value = state.value
                .filterNot { it.integrationId == settings.integrationId } + settings
        }
    }

    private class FakeIntegrationRegistry(
        private val integrationManifests: List<IntegrationManifest>,
    ) : IntegrationRegistry {
        override fun manifests(): List<IntegrationManifest> = integrationManifests

        override fun searchProviders(): List<SearchProvider> = emptyList()

        override fun discoveryProviders(): List<DiscoveryProvider> = emptyList()

        override fun metadataProviders(): List<MetadataProvider> = emptyList()

        override fun chapterEvidenceProviders(): List<ChapterEvidenceProvider> = emptyList()

        override fun ratingsProviders(): List<RatingsProvider> = emptyList()

        override fun trackingProviders(): List<TrackingProvider> = emptyList()
    }
}
