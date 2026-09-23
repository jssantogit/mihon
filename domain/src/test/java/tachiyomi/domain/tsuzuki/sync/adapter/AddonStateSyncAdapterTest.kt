package tachiyomi.domain.tsuzuki.sync.adapter

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.model.AddonSyncIntent
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
import tachiyomi.domain.tsuzuki.addon.repository.AddonSyncIntentRepository
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision
import tachiyomi.domain.tsuzuki.sync.service.SyncClock
import tachiyomi.domain.tsuzuki.sync.service.SyncRevisionSource

class AddonStateSyncAdapterTest {

    @Test
    fun `export merges installed addons with persisted missing-device intent`() = runTest {
        val addons = FakeAddonRepository(
            listOf(
                addon("pkg.installed", enabled = true),
                addon("pkg.disabled", enabled = false),
            ),
        )
        val intents = FakeAddonSyncIntentRepository(
            AddonSyncIntent(
                desiredPackageIds = setOf("pkg.missing"),
                enabledPackageIds = setOf("pkg.missing"),
            ),
        )
        val adapter = AddonStateSyncAdapter(
            addonRepository = addons,
            intentRepository = intents,
            revisionSource = FakeRevisionSource,
            clock = FakeClock,
        )

        val exported = adapter.exportDocument()
        val fields = exported.records.getValue("global").fields

        exported.kind shouldBe SyncDocumentKind.ADDON_STATE
        fields["desiredPackageIds"] shouldBe JsonArray(
            listOf(
                JsonPrimitive("pkg.disabled"),
                JsonPrimitive("pkg.installed"),
                JsonPrimitive("pkg.missing"),
            ),
        )
        fields["enabledPackageIds"] shouldBe JsonArray(
            listOf(JsonPrimitive("pkg.installed"), JsonPrimitive("pkg.missing")),
        )
    }

    @Test
    fun `apply stores non executable intent without enabling or uninstalling addons`() = runTest {
        val addons = FakeAddonRepository(listOf(addon("pkg.installed", enabled = false)))
        val intents = FakeAddonSyncIntentRepository(AddonSyncIntent())
        val adapter = AddonStateSyncAdapter(
            addonRepository = addons,
            intentRepository = intents,
            revisionSource = FakeRevisionSource,
            clock = FakeClock,
        )
        val remote = adapter.exportDocument().let { document ->
            val record = document.records.getValue("global")
            document.copy(
                records = mapOf(
                    "global" to record.copy(
                        updatedAtEpochMillis = 50,
                        fields = buildJsonObject {
                            put(
                                "desiredPackageIds",
                                JsonArray(
                                    listOf(
                                        JsonPrimitive("pkg.installed"),
                                        JsonPrimitive("pkg.not-installed"),
                                    ),
                                ),
                            )
                            put(
                                "enabledPackageIds",
                                JsonArray(
                                    listOf(
                                        JsonPrimitive("pkg.installed"),
                                        JsonPrimitive("pkg.not-installed"),
                                    ),
                                ),
                            )
                        },
                    ),
                ),
            )
        }

        adapter.applyDocument(remote)

        intents.get() shouldBe AddonSyncIntent(
            desiredPackageIds = setOf("pkg.installed", "pkg.not-installed"),
            enabledPackageIds = setOf("pkg.installed", "pkg.not-installed"),
        )
        addons.setEnabledCalls shouldBe 0
        addons.uninstallCalls shouldBe 0
    }

    private fun addon(id: String, enabled: Boolean) = InstalledAddon(
        id = AddonId(id),
        displayName = id,
        enabled = enabled,
        versionName = "1.0",
        mihonSourceIds = listOf(1),
        hasSettings = false,
    )

    private class FakeAddonRepository(
        initial: List<InstalledAddon>,
    ) : AddonRepository {
        private val state = MutableStateFlow(initial)
        var setEnabledCalls = 0
        var uninstallCalls = 0

        override fun observeInstalled(): Flow<List<InstalledAddon>> = state
        override suspend fun snapshot(): List<InstalledAddon> = state.value

        override suspend fun setEnabled(id: AddonId, enabled: Boolean) {
            setEnabledCalls += 1
        }

        override suspend fun uninstall(id: AddonId) {
            uninstallCalls += 1
        }
    }

    private class FakeAddonSyncIntentRepository(
        initial: AddonSyncIntent,
    ) : AddonSyncIntentRepository {
        private val state = MutableStateFlow(initial)

        override fun observe(): Flow<AddonSyncIntent> = state
        override suspend fun get(): AddonSyncIntent = state.value

        override suspend fun set(intent: AddonSyncIntent) {
            state.value = intent
        }

        override suspend fun removeDesired(id: AddonId) {
            state.value = state.value.copy(
                desiredPackageIds = state.value.desiredPackageIds - id.value,
                enabledPackageIds = state.value.enabledPackageIds - id.value,
            )
        }

        override suspend fun recordEnabled(id: AddonId, enabled: Boolean) {
            state.value = state.value.copy(
                desiredPackageIds = state.value.desiredPackageIds + id.value,
                enabledPackageIds = if (enabled) {
                    state.value.enabledPackageIds + id.value
                } else {
                    state.value.enabledPackageIds - id.value
                },
            )
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
