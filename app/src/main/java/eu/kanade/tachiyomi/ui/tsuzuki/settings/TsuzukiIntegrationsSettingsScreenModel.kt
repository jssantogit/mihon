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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
    USER_LISTS,
    CROSSWALK,
    REMOTE_LIBRARY,
    READING_CONTENT,
    DOWNLOADS,
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
    val capabilityEnabled: Map<TsuzukiIntegrationCapability, Boolean>,
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
                        capabilityEnabled = definition.capabilities.associateWith { capability ->
                            persisted?.configJson.capabilityEnabled(capability)
                        },
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
        capability: TsuzukiIntegrationCapability,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            val current = repository.get(id)
            val values = runCatching {
                Json.parseToJsonElement(current?.configJson ?: "{}") as? JsonObject
            }.getOrNull()
            val updated = buildJsonObject {
                values?.forEach { (key, value) -> put(key, value) }
                put(capability.configKey(), enabled)
            }
            repository.upsert(
                IntegrationSettings(
                    integrationId = id,
                    enabled = current?.enabled ?: false,
                    configJson = updated.toString(),
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

    private fun String?.capabilityEnabled(capability: TsuzukiIntegrationCapability): Boolean {
        val config = runCatching {
            Json.parseToJsonElement(this ?: "{}") as? JsonObject
        }.getOrNull()
        return config
            ?.get(capability.configKey())
            ?.jsonPrimitive
            ?.booleanOrNull
            ?: true
    }

    private fun String?.toConfigState(): TsuzukiIntegrationConfigState {
        val normalized = this?.trim().orEmpty()
        return if (normalized.isEmpty() || normalized == "{}") {
            TsuzukiIntegrationConfigState.DEFAULT
        } else {
            TsuzukiIntegrationConfigState.CUSTOM
        }
    }

    private fun TsuzukiIntegrationCapability.configKey(): String = name.lowercase()

    private data class Definition(
        val id: IntegrationId,
        val label: String,
        val capabilities: List<TsuzukiIntegrationCapability>,
        val authState: TsuzukiIntegrationAuthState,
    )

    private companion object {
        val SEARCH = TsuzukiIntegrationCapability.SEARCH
        val DISCOVERY = TsuzukiIntegrationCapability.DISCOVERY
        val METADATA = TsuzukiIntegrationCapability.METADATA
        val RATINGS = TsuzukiIntegrationCapability.RATINGS
        val TRACKING = TsuzukiIntegrationCapability.TRACKING
        val USER_LISTS = TsuzukiIntegrationCapability.USER_LISTS
        val CROSSWALK = TsuzukiIntegrationCapability.CROSSWALK
        val REMOTE_LIBRARY = TsuzukiIntegrationCapability.REMOTE_LIBRARY
        val READING_CONTENT = TsuzukiIntegrationCapability.READING_CONTENT
        val DOWNLOADS = TsuzukiIntegrationCapability.DOWNLOADS

        val DEFINITIONS = listOf(
            Definition(
                IntegrationId("kitsu"), "Kitsu",
                listOf(SEARCH, DISCOVERY, METADATA, RATINGS, TRACKING, USER_LISTS),
                TsuzukiIntegrationAuthState.MANAGED_EXTERNALLY,
            ),
            Definition(
                IntegrationId("mal"), "MyAnimeList",
                listOf(SEARCH, METADATA, RATINGS, TRACKING, USER_LISTS),
                TsuzukiIntegrationAuthState.MANAGED_EXTERNALLY,
            ),
            Definition(
                IntegrationId("mangaupdates"), "MangaUpdates",
                listOf(SEARCH, METADATA, RATINGS, TRACKING, USER_LISTS),
                TsuzukiIntegrationAuthState.MANAGED_EXTERNALLY,
            ),
            Definition(
                IntegrationId("mangabaka"), "MangaBaka",
                listOf(SEARCH, METADATA, CROSSWALK, TRACKING, USER_LISTS),
                TsuzukiIntegrationAuthState.MANAGED_EXTERNALLY,
            ),
            Definition(
                IntegrationId("bangumi"), "Bangumi",
                listOf(SEARCH, METADATA, RATINGS, TRACKING, USER_LISTS),
                TsuzukiIntegrationAuthState.MANAGED_EXTERNALLY,
            ),
            Definition(
                IntegrationId("shikimori"), "Shikimori",
                listOf(SEARCH, METADATA, RATINGS, TRACKING, USER_LISTS),
                TsuzukiIntegrationAuthState.MANAGED_EXTERNALLY,
            ),
            Definition(
                IntegrationId("hikka"), "Hikka",
                listOf(SEARCH, METADATA, RATINGS, TRACKING, USER_LISTS),
                TsuzukiIntegrationAuthState.MANAGED_EXTERNALLY,
            ),
            Definition(
                IntegrationId("anilist"), "AniList",
                listOf(TRACKING, USER_LISTS),
                TsuzukiIntegrationAuthState.MANAGED_EXTERNALLY,
            ),
            Definition(
                IntegrationId("komga"), "Komga",
                listOf(METADATA, REMOTE_LIBRARY, TRACKING),
                TsuzukiIntegrationAuthState.MANAGED_EXTERNALLY,
            ),
            Definition(
                IntegrationId("kavita"), "Kavita",
                listOf(METADATA, REMOTE_LIBRARY, TRACKING),
                TsuzukiIntegrationAuthState.MANAGED_EXTERNALLY,
            ),
            Definition(
                IntegrationId("suwayomi"), "Suwayomi",
                listOf(METADATA, REMOTE_LIBRARY, READING_CONTENT, DOWNLOADS, TRACKING),
                TsuzukiIntegrationAuthState.MANAGED_EXTERNALLY,
            ),
        )
    }
}