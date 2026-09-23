package tachiyomi.domain.tsuzuki.sync.adapter

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.model.IntegrationSettings
import tachiyomi.domain.tsuzuki.integration.repository.IntegrationSettingsRepository
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision
import tachiyomi.domain.tsuzuki.sync.service.SyncClock
import tachiyomi.domain.tsuzuki.sync.service.SyncRevisionSource

class IntegrationSettingsSyncAdapterTest {

    @Test
    fun `export excludes credential shaped config fields`() = runTest {
        val repository = FakeIntegrationSettingsRepository(
            listOf(
                IntegrationSettings(
                    integrationId = IntegrationId("example"),
                    enabled = true,
                    configJson = """
                        {
                          "region":"br",
                          "accessToken":"secret",
                          "nested":{"safe":1,"apiKey":"secret-key"}
                        }
                    """.trimIndent(),
                    updatedAt = 50,
                ),
            ),
        )
        val adapter = IntegrationSettingsSyncAdapter(
            repository = repository,
            json = Json,
            revisionSource = FakeRevisionSource,
            clock = FakeClock,
        )

        val document = adapter.exportDocument()

        document.kind shouldBe SyncDocumentKind.INTEGRATION_SETTINGS
        val fields = document.records.getValue("example").fields
        fields["enabled"]?.toString() shouldBe "true"
        val config = fields.getValue("config").jsonObject
        config["region"]?.toString() shouldBe "\"br\""
        config.containsKey("accessToken") shouldBe false
        config.getValue("nested").jsonObject.containsKey("apiKey") shouldBe false
        config.getValue("nested").jsonObject["safe"]?.toString() shouldBe "1"
    }

    @Test
    fun `apply updates safe config while preserving local secrets`() = runTest {
        val repository = FakeIntegrationSettingsRepository(
            listOf(
                IntegrationSettings(
                    integrationId = IntegrationId("example"),
                    enabled = false,
                    configJson = """
                        {
                          "region":"old",
                          "accessToken":"keep-me",
                          "nested":{"apiKey":"keep-nested","safe":0}
                        }
                    """.trimIndent(),
                    updatedAt = 10,
                ),
            ),
        )
        val adapter = IntegrationSettingsSyncAdapter(
            repository = repository,
            json = Json,
            revisionSource = FakeRevisionSource,
            clock = FakeClock,
        )
        val remote = adapter.exportDocument().let { doc ->
            val record = doc.records.getValue("example")
            doc.copy(
                records = mapOf(
                    "example" to record.copy(
                        updatedAtEpochMillis = 99,
                        fields = kotlinx.serialization.json.buildJsonObject {
                            put("enabled", JsonPrimitive(true))
                            put(
                                "config",
                                Json.parseToJsonElement(
                                    """{"region":"new","nested":{"safe":2}}""",
                                ),
                            )
                        },
                    ),
                ),
            )
        }

        adapter.applyDocument(remote)

        val applied = repository.get(IntegrationId("example"))!!
        applied.enabled shouldBe true
        applied.updatedAt shouldBe 99
        val config = Json.parseToJsonElement(applied.configJson).jsonObject
        config["region"]?.toString() shouldBe "\"new\""
        config["accessToken"]?.toString() shouldBe "\"keep-me\""
        config.getValue("nested").jsonObject["safe"]?.toString() shouldBe "2"
        config.getValue("nested").jsonObject["apiKey"]?.toString() shouldBe "\"keep-nested\""
    }

    private class FakeIntegrationSettingsRepository(
        initial: List<IntegrationSettings>,
    ) : IntegrationSettingsRepository {
        private val state = MutableStateFlow(initial)

        override suspend fun get(id: IntegrationId): IntegrationSettings? =
            state.value.firstOrNull { it.integrationId == id }

        override fun observeAll(): Flow<List<IntegrationSettings>> = state

        override suspend fun upsert(settings: IntegrationSettings) {
            state.value = state.value.filterNot {
                it.integrationId == settings.integrationId
            } + settings
        }
    }

    private data object FakeClock : SyncClock {
        override fun nowEpochMillis(): Long = 100
    }

    private data object FakeRevisionSource : SyncRevisionSource {
        override val deviceId: String = "device"
        private var sequence = 0L
        override fun nextRevision() = SyncRevision(deviceId, ++sequence)
    }
}
