package eu.kanade.tachiyomi.ui.tsuzuki.settings

import eu.kanade.tachiyomi.data.tsuzuki.integration.DefaultIntegrationManifests
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKey
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
            "kitsu",
            "mal",
            "mangaupdates",
            "mangabaka",
            "bangumi",
            "shikimori",
            "hikka",
            "anilist",
            "komga",
            "kavita",
            "suwayomi",
        )
        state.items.all { !it.enabled } shouldBe true
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
    fun `restricted AniList catalog capabilities are not user configurable`() = runTest(dispatcher) {
        val model = TsuzukiIntegrationsSettingsScreenModel(FakeIntegrationSettingsRepository(), registry)
        advanceUntilIdle()

        val item = model.state.value
            .shouldBeInstanceOf<TsuzukiIntegrationsSettingsState.Loaded>()
            .items
            .single { it.id.value == "anilist" }

        item.capabilities shouldContainExactly listOf(
            IntegrationCapability.TRACKING,
            IntegrationCapability.USER_LISTS,
        )
        item.restrictedCapabilities shouldContainKey IntegrationCapability.SEARCH
        item.restrictedCapabilities shouldContainKey IntegrationCapability.METADATA_BASIC
    }

    @Test
    fun `personal server capabilities stay configurable within server scope`() = runTest(dispatcher) {
        val model = TsuzukiIntegrationsSettingsScreenModel(FakeIntegrationSettingsRepository(), registry)
        advanceUntilIdle()

        val item = model.state.value
            .shouldBeInstanceOf<TsuzukiIntegrationsSettingsState.Loaded>()
            .items
            .single { it.id.value == "komga" }

        IntegrationCapability.METADATA_BASIC in item.capabilities shouldBe true
        IntegrationCapability.REMOTE_LIBRARY in item.capabilities shouldBe true
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
