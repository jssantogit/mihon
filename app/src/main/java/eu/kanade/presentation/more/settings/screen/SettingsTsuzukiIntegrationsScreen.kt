package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.track.components.TrackLogoIcon
import eu.kanade.presentation.tsuzuki.integration.IntegrationBrandIcon
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.hikka.Hikka
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.data.track.shikimori.Shikimori
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegrationSettingsItem
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegrationsSettingsScreenModel
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegrationsSettingsState
import mihon.app.di.appGraph
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCategory
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

class SettingsTsuzukiIntegrationsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel =
            metroViewModel<TsuzukiIntegrationsSettingsScreenModel>()
        val state by screenModel.state.collectAsStateWithLifecycle()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(MR.strings.tsuzuki_integrations_title)) },
                    navigationIcon = {
                        TextButton(onClick = navigator::pop) {
                            Text(stringResource(MR.strings.tsuzuki_navigation_back))
                        }
                    },
                )
            },
        ) { contentPadding ->
            when (val current = state) {
                TsuzukiIntegrationsSettingsState.Loading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(contentPadding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is TsuzukiIntegrationsSettingsState.Loaded -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(contentPadding),
                    ) {
                        val generalItems = current.items.filter {
                            it.category == IntegrationCategory.GENERAL
                        }
                        if (generalItems.isNotEmpty()) {
                            item(key = "header_GENERAL") {
                                Text(
                                    text = stringResource(IntegrationCategory.GENERAL.labelRes()),
                                    modifier = Modifier.padding(
                                        horizontal = 16.dp,
                                        vertical = 12.dp,
                                    ),
                                )
                            }
                            items(
                                items = generalItems,
                                key = { it.id.value },
                            ) { item ->
                                IntegrationSettingRow(
                                    item = item,
                                    onEnabledChange = {
                                        screenModel.setEnabled(
                                            id = item.id,
                                            enabled = it,
                                        )
                                    },
                                    onOpen = {
                                        navigator.push(SettingsTsuzukiIntegrationDetailScreen(item.id.value))
                                    },
                                )
                                HorizontalDivider()
                            }

                        }

                        IntegrationCategory.entries
                            .filterNot { it == IntegrationCategory.GENERAL }
                            .forEach { category ->
                                val categoryItems = current.items.filter { it.category == category }
                                if (categoryItems.isEmpty()) return@forEach

                                item(key = "header_${category.name}") {
                                    Text(
                                        text = stringResource(category.labelRes()),
                                        modifier = Modifier.padding(
                                            horizontal = 16.dp,
                                            vertical = 12.dp,
                                        ),
                                    )
                                }

                                items(
                                    items = categoryItems,
                                    key = { it.id.value },
                                ) { item ->
                                    IntegrationSettingRow(
                                        item = item,
                                        onEnabledChange = {
                                            screenModel.setEnabled(
                                                id = item.id,
                                                enabled = it,
                                            )
                                        },
                                        onOpen = {
                                            navigator.push(SettingsTsuzukiIntegrationDetailScreen(item.id.value))
                                        },
                                    )
                                    HorizontalDivider()
                                }
                            }
                    }
                }
            }
        }
    }
}

class SettingsTsuzukiIntegrationDetailScreen(
    private val integrationId: String,
) : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = metroViewModel<TsuzukiIntegrationsSettingsScreenModel>()
        val state by screenModel.state.collectAsStateWithLifecycle()
        val item = (state as? TsuzukiIntegrationsSettingsState.Loaded)
            ?.items
            ?.firstOrNull { it.id.value == integrationId }
        val tracker = remember(item?.legacyTrackerId) {
            item?.legacyTrackerId?.let { context.appGraph.trackerManager.get(it) }
        }
        var malClientIdConfigured by remember(tracker) {
            mutableStateOf((tracker as? MyAnimeList)?.hasClientId() ?: true)
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(item?.label ?: stringResource(MR.strings.tsuzuki_integration_fallback_title)) },
                    navigationIcon = {
                        TextButton(onClick = navigator::pop) {
                            Text(stringResource(MR.strings.tsuzuki_navigation_back))
                        }
                    },
                )
            },
        ) { contentPadding ->
            if (item == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                ) {
                    providerDescriptionRes(item.id.value)?.let { description ->
                        item(key = "provider_summary") {
                            ProviderSummary(
                                tracker = tracker,
                                description = stringResource(description),
                            )
                        }
                    }

                    when (item.id.value) {
                        "mal" -> item(key = "mal_client_id") {
                            MalClientIdConfiguration(
                                tracker = tracker as? MyAnimeList,
                                onConfiguredChanged = { configured ->
                                    malClientIdConfigured = configured
                                    if (!configured && item.enabled) {
                                        screenModel.setEnabled(item.id, false)
                                    }
                                },
                            )
                        }
                        "shikimori" -> item(key = "shikimori_oauth_app") {
                            ShikimoriCredentialsConfiguration(tracker as? Shikimori)
                        }
                        "hikka" -> item(key = "hikka_oauth_app") {
                            HikkaCredentialsConfiguration(tracker as? Hikka)
                        }
                    }

                    if (item.configurableCapabilities.isNotEmpty()) {
                        item(key = "integration_enabled") {
                            ListItem(
                                headlineContent = {
                                    Text(
                                        if (item.id.value == "tsuzuki") {
                                            stringResource(MR.strings.tsuzuki_integration_enable_tsuzuki)
                                        } else {
                                            stringResource(
                                                MR.strings.tsuzuki_integration_use_provider,
                                                item.label,
                                            )
                                        },
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = item.enabled,
                                        onCheckedChange = {
                                            screenModel.setEnabled(item.id, it)
                                        },
                                        enabled = item.id.value != "mal" ||
                                            malClientIdConfigured ||
                                            item.enabled,
                                    )
                                },
                            )
                        }

                        val configurable = item.capabilities.filter {
                            it in item.configurableCapabilities
                        }
                        items(
                            items = configurable,
                            key = { it.name },
                        ) { capability ->
                            ListItem(
                                headlineContent = { Text(stringResource(capability.labelRes())) },
                                trailingContent = {
                                    Switch(
                                        checked = item.capabilityEnabled[capability] ?: true,
                                        onCheckedChange = {
                                            screenModel.setCapabilityEnabled(item.id, capability, it)
                                        },
                                        enabled = item.enabled,
                                    )
                                },
                                modifier = Modifier.alpha(if (item.enabled) 1f else 0.45f),
                            )
                        }
                    }

                    if (item.id.value == "tsuzuki") {
                        item(key = "tracking_behavior") {
                            ListItem(
                                headlineContent = {
                                    Text(stringResource(MR.strings.tsuzuki_integrations_tracking_behavior_title))
                                },
                                supportingContent = {
                                    Text(stringResource(MR.strings.tsuzuki_integrations_tracking_behavior_summary))
                                },
                                trailingContent = {
                                    TextButton(
                                        onClick = {
                                            navigator.push(SettingsTsuzukiTrackingBehaviorScreen)
                                        },
                                    ) {
                                        Text(stringResource(MR.strings.action_settings))
                                    }
                                },
                                modifier = Modifier.clickable {
                                    navigator.push(SettingsTsuzukiTrackingBehaviorScreen)
                                },
                            )
                        }
                    }

                    if (item.restrictedCapabilities.isNotEmpty()) {
                        item(key = "restricted_summary") {
                            ListItem(
                                headlineContent = {
                                    Text(stringResource(MR.strings.tsuzuki_integration_catalog_unavailable))
                                },
                                supportingContent = {
                                    Text(stringResource(MR.strings.tsuzuki_integration_catalog_unavailable_summary))
                                },
                            )
                        }
                    }

                    if (
                        item.supportsTracking &&
                        item.legacyTrackerId != null
                    ) {
                        item(key = "account_tracking") {
                            ListItem(
                                headlineContent = {
                                    Text(stringResource(MR.strings.tsuzuki_integration_account_tracking))
                                },
                                supportingContent = {
                                    Text(
                                        stringResource(
                                            MR.strings.tsuzuki_integration_account_tracking_summary,
                                            item.label,
                                        ),
                                    )
                                },
                                trailingContent = {
                                    TextButton(
                                        onClick = {
                                            navigator.push(
                                                SettingsTsuzukiTrackingServiceScreen(item.legacyTrackerId),
                                            )
                                        },
                                    ) {
                                        Text(stringResource(MR.strings.action_settings))
                                    }
                                },
                                modifier = Modifier.clickable {
                                    navigator.push(
                                        SettingsTsuzukiTrackingServiceScreen(item.legacyTrackerId),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MalClientIdConfiguration(
    tracker: MyAnimeList?,
    onConfiguredChanged: (Boolean) -> Unit,
) {
    var savedClientId by remember(tracker) { mutableStateOf(tracker?.getClientId().orEmpty()) }
    var clientId by remember(tracker) { mutableStateOf(savedClientId) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(MR.strings.tsuzuki_mal_client_id_title))
        Text(stringResource(MR.strings.tsuzuki_mal_client_id_summary))
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it },
            label = { Text(stringResource(MR.strings.tsuzuki_mal_client_id_title)) },
            supportingText = {
                Text(stringResource(MR.strings.tsuzuki_mal_client_id_setup))
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        TextButton(
            enabled = tracker != null && clientId.trim() != savedClientId,
            onClick = {
                tracker?.setClientId(clientId)
                savedClientId = tracker?.getClientId().orEmpty()
                clientId = savedClientId
                onConfiguredChanged(savedClientId.isNotBlank())
            },
        ) {
            Text(stringResource(MR.strings.action_save))
        }
    }
}

@Composable
private fun ShikimoriCredentialsConfiguration(tracker: Shikimori?) {
    ApplicationCredentialsConfiguration(
        clientId = tracker?.getClientId().orEmpty(),
        clientSecret = tracker?.getClientSecret().orEmpty(),
        clientIdLabel = stringResource(MR.strings.tsuzuki_oauth_client_id_title),
        clientSecretLabel = stringResource(MR.strings.tsuzuki_oauth_client_secret_title),
        summary = stringResource(MR.strings.tsuzuki_shikimori_credentials_summary),
        setup = stringResource(MR.strings.tsuzuki_shikimori_credentials_setup),
        onSave = { clientId, clientSecret ->
            tracker?.setApplicationCredentials(clientId, clientSecret)
        },
    )
}

@Composable
private fun HikkaCredentialsConfiguration(tracker: Hikka?) {
    ApplicationCredentialsConfiguration(
        clientId = tracker?.getClientReference().orEmpty(),
        clientSecret = tracker?.getClientSecret().orEmpty(),
        clientIdLabel = stringResource(MR.strings.tsuzuki_hikka_reference_title),
        clientSecretLabel = stringResource(MR.strings.tsuzuki_hikka_client_secret_title),
        summary = stringResource(MR.strings.tsuzuki_hikka_credentials_summary),
        setup = stringResource(MR.strings.tsuzuki_hikka_credentials_setup),
        onSave = { clientReference, clientSecret ->
            tracker?.setApplicationCredentials(clientReference, clientSecret)
        },
    )
}

@Composable
private fun ApplicationCredentialsConfiguration(
    clientId: String,
    clientSecret: String,
    clientIdLabel: String,
    clientSecretLabel: String,
    summary: String,
    setup: String,
    onSave: (String, String) -> Unit,
) {
    var savedClientId by remember(clientId) { mutableStateOf(clientId) }
    var savedClientSecret by remember(clientSecret) { mutableStateOf(clientSecret) }
    var editedClientId by remember(clientId) { mutableStateOf(clientId) }
    var editedClientSecret by remember(clientSecret) { mutableStateOf(clientSecret) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(summary)
        OutlinedTextField(
            value = editedClientId,
            onValueChange = { editedClientId = it },
            label = { Text(clientIdLabel) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = editedClientSecret,
            onValueChange = { editedClientSecret = it },
            label = { Text(clientSecretLabel) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Text(setup)
        TextButton(
            enabled = editedClientId.trim() != savedClientId ||
                editedClientSecret.trim() != savedClientSecret,
            onClick = {
                onSave(editedClientId, editedClientSecret)
                savedClientId = editedClientId.trim()
                savedClientSecret = editedClientSecret.trim()
                editedClientId = savedClientId
                editedClientSecret = savedClientSecret
            },
        ) {
            Text(stringResource(MR.strings.action_save))
        }
    }
}

@Composable
private fun ProviderSummary(
    tracker: Tracker?,
    description: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tracker?.let { TrackLogoIcon(it) }
        Text(
            text = description,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun IntegrationSettingRow(
    item: TsuzukiIntegrationSettingsItem,
    onEnabledChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    val tracker = remember(item.legacyTrackerId) {
        item.legacyTrackerId?.let { context.appGraph.trackerManager.get(it) }
    }

    val malClientIdConfigured = (tracker as? MyAnimeList)?.hasClientId() ?: true

    ListItem(
        headlineContent = { Text(item.label) },
        supportingContent = if (item.id.value == "mal" && !malClientIdConfigured) {
            {
                Text(stringResource(MR.strings.tsuzuki_mal_client_id_required))
            }
        } else {
            null
        },
        leadingContent = when {
            item.id.value == "tsuzuki" -> {
                {
                    IntegrationBrandIcon(
                        providerId = "tsuzuki",
                        size = 32.dp,
                    )
                }
            }
            tracker != null -> tracker.let { resolvedTracker ->
                {
                    TrackLogoIcon(resolvedTracker)
                }
            }
            else -> null
        },
        trailingContent = {
            if (item.configurableCapabilities.isNotEmpty()) {
                Switch(
                    checked = item.enabled,
                    onCheckedChange = onEnabledChange,
                    enabled = item.enabled || malClientIdConfigured,
                )
            }
        },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

private fun IntegrationCategory.labelRes(): StringResource = when (this) {
    IntegrationCategory.GENERAL -> MR.strings.tsuzuki_integrations_category_general
    IntegrationCategory.METADATA_SERVICE -> MR.strings.tsuzuki_integrations_category_metadata
    IntegrationCategory.PERSONAL_SERVER -> MR.strings.tsuzuki_integrations_category_servers
    IntegrationCategory.COMPATIBILITY -> MR.strings.tsuzuki_integrations_category_compatibility
}

private fun IntegrationCapability.labelRes(): StringResource = when (this) {
    IntegrationCapability.SEARCH -> MR.strings.tsuzuki_integration_capability_search
    IntegrationCapability.DISCOVERY -> MR.strings.tsuzuki_integration_capability_discovery
    IntegrationCapability.METADATA_BASIC -> MR.strings.tsuzuki_integration_capability_metadata_basic
    IntegrationCapability.METADATA_ARTWORK -> MR.strings.tsuzuki_integration_capability_metadata_artwork
    IntegrationCapability.METADATA_EDITORIAL -> MR.strings.tsuzuki_integration_capability_metadata_editorial
    IntegrationCapability.METADATA_STAFF -> MR.strings.tsuzuki_integration_capability_metadata_staff
    IntegrationCapability.RATINGS -> MR.strings.tsuzuki_integration_capability_ratings
    IntegrationCapability.RELATIONS -> MR.strings.tsuzuki_integration_capability_relations
    IntegrationCapability.CROSSWALK -> MR.strings.tsuzuki_integration_capability_crosswalk
    IntegrationCapability.TRACKING -> MR.strings.tsuzuki_integration_capability_tracking
    IntegrationCapability.USER_LISTS -> MR.strings.tsuzuki_integration_capability_user_lists
    IntegrationCapability.REMOTE_LIBRARY -> MR.strings.tsuzuki_integration_capability_remote_library
    IntegrationCapability.READING_CONTENT -> MR.strings.tsuzuki_integration_capability_reading_content
    IntegrationCapability.DOWNLOADS -> MR.strings.tsuzuki_integration_capability_downloads
}

private fun providerDescriptionRes(integrationId: String): StringResource? = when (integrationId) {
    "kitsu" -> MR.strings.tsuzuki_integration_provider_kitsu
    "mal" -> MR.strings.tsuzuki_integration_provider_mal
    "mangaupdates" -> MR.strings.tsuzuki_integration_provider_mangaupdates
    "bangumi" -> MR.strings.tsuzuki_integration_provider_bangumi
    "shikimori" -> MR.strings.tsuzuki_integration_provider_shikimori
    "hikka" -> MR.strings.tsuzuki_integration_provider_hikka
    "komga" -> MR.strings.tsuzuki_integration_provider_komga
    "kavita" -> MR.strings.tsuzuki_integration_provider_kavita
    "suwayomi" -> MR.strings.tsuzuki_integration_provider_suwayomi
    else -> null
}
