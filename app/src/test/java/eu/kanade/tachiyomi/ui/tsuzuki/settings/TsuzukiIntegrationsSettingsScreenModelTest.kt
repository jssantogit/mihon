package eu.kanade.tachiyomi.ui.tsuzuki.settings

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
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.model.IntegrationSettings
import tachiyomi.domain.tsuzuki.integration.repository.IntegrationSettingsRepository

@OptIn(ExperimentalCoroutinesApi::class)
class TsuzukiIntegrationsSettingsScreenModelTest {

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
    fun `all unified integrations are disabled by default when no settings rows exist`() = runTest(dispatcher) {
        val repository = FakeIntegrationSettingsRepository()
        val model = TsuzukiIntegrationsSettingsScreenModel(repository)

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
        val model = TsuzukiIntegrationsSettingsScreenModel(repository)
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
        val model = TsuzukiIntegrationsSettingsScreenModel(repository)
        advanceUntilIdle()

        model.setEnabled(IntegrationId("kitsu"), true)
        model.setCapabilityEnabled(
            IntegrationId("kitsu"),
            TsuzukiIntegrationCapability.DISCOVERY,
            false,
        )
        advanceUntilIdle()

        val item = model.state.value
            .shouldBeInstanceOf<TsuzukiIntegrationsSettingsState.Loaded>()
            .items
            .single { it.id.value == "kitsu" }
        item.enabled shouldBe true
        item.capabilityEnabled[TsuzukiIntegrationCapability.DISCOVERY] shouldBe false
        item.capabilityEnabled[TsuzukiIntegrationCapability.SEARCH] shouldBe true
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
}
