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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.track.components.TrackLogoIcon
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.track.Tracker
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
                            HorizontalDivider()
                        }

                        IntegrationCategory.entries.forEach { category ->
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

                    if (item.configurableCapabilities.isNotEmpty()) {
                        item(key = "integration_enabled") {
                            ListItem(
                                headlineContent = {
                                    Text(
                                        stringResource(
                                            MR.strings.tsuzuki_integration_use_provider,
                                            item.label,
                                        ),
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = item.enabled,
                                        onCheckedChange = {
                                            screenModel.setEnabled(item.id, it)
                                        },
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

    ListItem(
        headlineContent = { Text(item.label) },
        leadingContent = tracker?.let {
            {
                TrackLogoIcon(it)
            }
        },
        trailingContent = {
            if (item.configurableCapabilities.isNotEmpty()) {
                Switch(
                    checked = item.enabled,
                    onCheckedChange = onEnabledChange,
                )
            }
        },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

private fun IntegrationCategory.labelRes(): StringResource = when (this) {
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
