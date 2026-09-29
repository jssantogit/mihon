package eu.kanade.tachiyomi.ui.tsuzuki.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.data.tsuzuki.integration.IntegrationSettingsConfig
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.model.CapabilityPolicy
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCategory
import tachiyomi.domain.tsuzuki.integration.model.IntegrationManifest
import tachiyomi.domain.tsuzuki.integration.model.IntegrationSettings
import tachiyomi.domain.tsuzuki.integration.repository.IntegrationSettingsRepository
import kotlin.time.Clock

enum class TsuzukiIntegrationConfigState {
    DEFAULT,
    CUSTOM,
}

enum class TsuzukiIntegrationAuthState {
    NOT_REQUIRED,
    MANAGED_EXTERNALLY,
}

@Immutable
data class TsuzukiIntegrationSettingsItem(
    val id: IntegrationId,
    val label: String,
    val category: IntegrationCategory,
    val enabled: Boolean,
    val legacyTrackerId: Long?,
    val supportsTracking: Boolean,
    val capabilities: List<IntegrationCapability>,
    val configurableCapabilities: Set<IntegrationCapability>,
    val restrictedCapabilities: Map<IntegrationCapability, CapabilityPolicy>,
    val policies: Map<IntegrationCapability, CapabilityPolicy>,
    val configState: TsuzukiIntegrationConfigState,
    val authState: TsuzukiIntegrationAuthState,
    val capabilityEnabled: Map<IntegrationCapability, Boolean>,
)

@Immutable
sealed interface TsuzukiIntegrationsSettingsState {
    data object Loading : TsuzukiIntegrationsSettingsState

    data class Loaded(
        val items: List<TsuzukiIntegrationSettingsItem>,
    ) : TsuzukiIntegrationsSettingsState
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class TsuzukiIntegrationsSettingsScreenModel(
    private val repository: IntegrationSettingsRepository,
    private val registry: IntegrationRegistry,
) : ViewModel() {

    val state: StateFlow<TsuzukiIntegrationsSettingsState> = repository
        .observeAll()
        .map { settings ->
            val latest = settings
                .groupBy { it.integrationId }
                .mapValues { (_, rows) ->
                    rows.maxByOrNull(IntegrationSettings::updatedAt)
                }
            TsuzukiIntegrationsSettingsState.Loaded(
                items = registry.manifests().map { manifest ->
                    val persisted = latest[manifest.integrationId]
                    val config = IntegrationSettingsConfig.decode(persisted?.configJson)
                    val visibleCapabilities = manifest.visibleCapabilities()
                    TsuzukiIntegrationSettingsItem(
                        id = manifest.integrationId,
                        label = manifest.displayName,
                        category = manifest.category,
                        enabled = persisted?.enabled ?: false,
                        legacyTrackerId = manifest.legacyTrackerId,
                        supportsTracking = IntegrationCapability.TRACKING in manifest.capabilities,
                        capabilities = visibleCapabilities,
                        configurableCapabilities = visibleCapabilities
                            .filterTo(mutableSetOf()) {
                                it in CONFIGURABLE_CAPABILITIES &&
                                    manifest.allowsGlobalResolution(it)
                            },
                        restrictedCapabilities = manifest.restrictedCapabilities(),
                        policies = manifest.capabilities,
                        configState = if (config.isDefault()) {
                            TsuzukiIntegrationConfigState.DEFAULT
                        } else {
                            TsuzukiIntegrationConfigState.CUSTOM
                        },
                        authState = manifest.authState(),
                        capabilityEnabled = visibleCapabilities
                            .filter {
                                it in CONFIGURABLE_CAPABILITIES &&
                                    manifest.allowsGlobalResolution(it)
                            }
                            .associateWith(config::capabilityEnabled),
                    )
                },
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = TsuzukiIntegrationsSettingsState.Loading,
        )

    fun setCapabilityEnabled(
        id: IntegrationId,
        capability: IntegrationCapability,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            val current = repository.get(id)
            val updated = IntegrationSettingsConfig.decode(current?.configJson)
                .withCapability(capability, enabled)
            repository.upsert(
                IntegrationSettings(
                    integrationId = id,
                    enabled = current?.enabled ?: false,
                    configJson = updated.encode(),
                    updatedAt = Clock.System.now().toEpochMilliseconds(),
                ),
            )
        }
    }

    fun setEnabled(
        id: IntegrationId,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            val current = repository.get(id)
            repository.upsert(
                IntegrationSettings(
                    integrationId = id,
                    enabled = enabled,
                    configJson = current?.configJson ?: "{}",
                    updatedAt = Clock.System.now().toEpochMilliseconds(),
                ),
            )
        }
    }

    private fun IntegrationManifest.visibleCapabilities(): List<IntegrationCapability> =
        capabilities.keys.filter { capability ->
            capability !in ACCOUNT_ONLY_CAPABILITIES &&
                (
                    category == IntegrationCategory.PERSONAL_SERVER ||
                        allowsGlobalResolution(capability)
                    )
        }

    private fun IntegrationManifest.restrictedCapabilities(): Map<IntegrationCapability, CapabilityPolicy> =
        capabilities.filter { (capability, policy) ->
            capability !in ACCOUNT_ONLY_CAPABILITIES &&
                capability !in visibleCapabilities() &&
                !policy.policy.allowsGlobalResolution
        }

    private fun IntegrationManifest.authState(): TsuzukiIntegrationAuthState =
        if (
            IntegrationCapability.TRACKING in capabilities ||
            IntegrationCapability.USER_LISTS in capabilities ||
            category == IntegrationCategory.PERSONAL_SERVER
        ) {
            TsuzukiIntegrationAuthState.MANAGED_EXTERNALLY
        } else {
            TsuzukiIntegrationAuthState.NOT_REQUIRED
        }

    private companion object {
        val ACCOUNT_ONLY_CAPABILITIES = setOf(
            IntegrationCapability.TRACKING,
            IntegrationCapability.USER_LISTS,
        )

        val CONFIGURABLE_CAPABILITIES = setOf(
            IntegrationCapability.SEARCH,
            IntegrationCapability.DISCOVERY,
            IntegrationCapability.METADATA_BASIC,
            IntegrationCapability.METADATA_ARTWORK,
            IntegrationCapability.METADATA_EDITORIAL,
            IntegrationCapability.RATINGS,
        )
    }
}
