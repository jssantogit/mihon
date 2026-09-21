package eu.kanade.tachiyomi.ui.tsuzuki.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.model.IntegrationSettings
import tachiyomi.domain.tsuzuki.integration.repository.IntegrationSettingsRepository
import kotlin.time.Clock

enum class TsuzukiIntegrationCapability {
    SEARCH,
    DISCOVERY,
    METADATA,
    RATINGS,
    CHAPTER_EVIDENCE,
    TRACKING,
}

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
    val enabled: Boolean,
    val capabilities: List<TsuzukiIntegrationCapability>,
    val configState: TsuzukiIntegrationConfigState,
    val authState: TsuzukiIntegrationAuthState,
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
                items = DEFINITIONS.map { definition ->
                    val persisted = latest[definition.id]
                    TsuzukiIntegrationSettingsItem(
                        id = definition.id,
                        label = definition.label,
                        enabled = persisted?.enabled ?: false,
                        capabilities = definition.capabilities,
                        configState = persisted
                            ?.configJson
                            .toConfigState(),
                        authState = definition.authState,
                    )
                },
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = TsuzukiIntegrationsSettingsState.Loading,
        )

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

    private fun String?.toConfigState(): TsuzukiIntegrationConfigState {
        val normalized = this?.trim().orEmpty()
        return if (normalized.isEmpty() || normalized == "{}") {
            TsuzukiIntegrationConfigState.DEFAULT
        } else {
            TsuzukiIntegrationConfigState.CUSTOM
        }
    }

    private data class Definition(
        val id: IntegrationId,
        val label: String,
        val capabilities: List<TsuzukiIntegrationCapability>,
        val authState: TsuzukiIntegrationAuthState,
    )

    private companion object {
        val DEFINITIONS = listOf(
            Definition(
                id = IntegrationId("kitsu"),
                label = "Kitsu",
                capabilities = listOf(
                    TsuzukiIntegrationCapability.SEARCH,
                    TsuzukiIntegrationCapability.DISCOVERY,
                    TsuzukiIntegrationCapability.METADATA,
                ),
                authState = TsuzukiIntegrationAuthState.NOT_REQUIRED,
            ),
            Definition(
                id = IntegrationId("mal"),
                label = "MyAnimeList",
                capabilities = listOf(
                    TsuzukiIntegrationCapability.SEARCH,
                    TsuzukiIntegrationCapability.METADATA,
                    TsuzukiIntegrationCapability.RATINGS,
                ),
                authState = TsuzukiIntegrationAuthState.MANAGED_EXTERNALLY,
            ),
        )
    }
}
