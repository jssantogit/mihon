package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegrationAuthState
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegrationConfigState
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegrationSettingsItem
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegrationsSettingsScreenModel
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegrationsSettingsState
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCategory
import tachiyomi.domain.tsuzuki.integration.model.IntegrationPolicy
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
                            Text("Voltar")
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
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = metroViewModel<TsuzukiIntegrationsSettingsScreenModel>()
        val state by screenModel.state.collectAsStateWithLifecycle()
        val item = (state as? TsuzukiIntegrationsSettingsState.Loaded)
            ?.items
            ?.firstOrNull { it.id.value == integrationId }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(item?.label ?: "Integração") },
                    navigationIcon = {
                        TextButton(onClick = navigator::pop) { Text("Voltar") }
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
                    if (item.configurableCapabilities.isNotEmpty()) {
                        item {
                            ListItem(
                                headlineContent = { Text(stringResource(MR.strings.tsuzuki_integration_enabled)) },
                                supportingContent = {
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
                    }
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(MR.strings.tsuzuki_integration_type)) },
                            supportingContent = { Text(stringResource(item.category.labelRes())) },
                        )
                    }
                    providerDescriptionRes(item.id.value)?.let { description ->
                        item {
                            ListItem(
                                headlineContent = { Text(item.label) },
                                supportingContent = { Text(stringResource(description)) },
                            )
                        }
                    }
                    if (item.capabilities.isNotEmpty()) {
                        item {
                            ListItem(
                                headlineContent = { Text(stringResource(MR.strings.tsuzuki_integration_features)) },
                                supportingContent = {
                                    Text(stringResource(MR.strings.tsuzuki_integration_features_summary))
                                },
                            )
                        }
                        items(
                            items = item.capabilities,
                            key = { it.name },
                        ) { capability ->
                            val policy = item.policies[capability]?.policy
                            ListItem(
                                headlineContent = { Text(stringResource(capability.labelRes())) },
                                supportingContent = {
                                    if (policy != null && policy != IntegrationPolicy.ALLOWED) {
                                        Text(stringResource(policy.labelRes()))
                                    }
                                },
                                trailingContent = {
                                    if (capability in item.configurableCapabilities) {
                                        Switch(
                                            checked = item.capabilityEnabled[capability] ?: true,
                                            onCheckedChange = {
                                                screenModel.setCapabilityEnabled(item.id, capability, it)
                                            },
                                        )
                                    }
                                },
                            )
                        }
                    }
                    if (item.restrictedCapabilities.isNotEmpty()) {
                        item {
                            ListItem(
                                headlineContent = {
                                    Text(stringResource(MR.strings.tsuzuki_integration_blocked_features))
                                },
                                supportingContent = {
                                    Text(stringResource(MR.strings.tsuzuki_integration_blocked_features_summary))
                                },
                            )
                        }
                        items(
                            items = item.restrictedCapabilities.entries.toList(),
                            key = { it.key.name },
                        ) { (capability, policy) ->
                            ListItem(
                                headlineContent = { Text(stringResource(capability.labelRes())) },
                                supportingContent = { Text(stringResource(policy.policy.labelRes())) },
                            )
                        }
                    }
                    if (
                        item.supportsTracking &&
                        item.legacyTrackerId != null
                    ) {
                        item {
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
                                modifier = Modifier.clickable {
                                    navigator.push(
                                        SettingsTsuzukiTrackingServiceScreen(item.legacyTrackerId),
                                    )
                                },
                            )
                        }
                    }
                    if (item.configurableCapabilities.isNotEmpty()) {
                        item {
                            ListItem(
                                headlineContent = {
                                    Text(stringResource(MR.strings.tsuzuki_integration_configuration))
                                },
                                supportingContent = {
                                    Text(stringResource(item.configState.labelRes()))
                                },
                            )
                        }
                    }
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(MR.strings.tsuzuki_integration_authentication)) },
                            supportingContent = { Text(stringResource(item.authState.labelRes())) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IntegrationSettingRow(
    item: TsuzukiIntegrationSettingsItem,
    onEnabledChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(item.label) },
        supportingContent = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(item.category.labelRes()))
                if (item.restrictedCapabilities.isNotEmpty()) {
                    Text(stringResource(MR.strings.tsuzuki_integration_blocked_count, item.restrictedCapabilities.size))
                }
                if (item.configurableCapabilities.isNotEmpty()) {
                    Text(
                        stringResource(MR.strings.tsuzuki_integration_configuration) +
                            ": " +
                            stringResource(item.configState.labelRes()),
                    )
                }
                Text(
                    stringResource(MR.strings.tsuzuki_integration_authentication) +
                        ": " +
                        stringResource(item.authState.labelRes()),
                )
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

private fun IntegrationPolicy.labelRes(): StringResource = when (this) {
    IntegrationPolicy.ALLOWED -> MR.strings.tsuzuki_integration_policy_allowed
    IntegrationPolicy.ALLOWED_WITH_ATTRIBUTION -> MR.strings.tsuzuki_integration_policy_attribution
    IntegrationPolicy.USER_OWNED_DATA -> MR.strings.tsuzuki_integration_policy_user_owned
    IntegrationPolicy.COMMERCIAL_RESTRICTION -> MR.strings.tsuzuki_integration_policy_commercial
    IntegrationPolicy.PERMISSION_REQUIRED -> MR.strings.tsuzuki_integration_policy_permission
    IntegrationPolicy.UNVERIFIED -> MR.strings.tsuzuki_integration_policy_unverified
}

private fun TsuzukiIntegrationConfigState.labelRes(): StringResource = when (this) {
    TsuzukiIntegrationConfigState.DEFAULT -> MR.strings.tsuzuki_integration_config_default
    TsuzukiIntegrationConfigState.CUSTOM -> MR.strings.tsuzuki_integration_config_custom
}

private fun TsuzukiIntegrationAuthState.labelRes(): StringResource = when (this) {
    TsuzukiIntegrationAuthState.NOT_REQUIRED -> MR.strings.tsuzuki_integration_auth_not_required
    TsuzukiIntegrationAuthState.MANAGED_EXTERNALLY -> MR.strings.tsuzuki_integration_auth_existing_session
}

private fun providerDescriptionRes(integrationId: String): StringResource? = when (integrationId) {
    "kitsu" -> MR.strings.tsuzuki_integration_provider_kitsu
    "mal" -> MR.strings.tsuzuki_integration_provider_mal
    "mangaupdates" -> MR.strings.tsuzuki_integration_provider_mangaupdates
    "mangabaka" -> MR.strings.tsuzuki_integration_provider_mangabaka
    "bangumi" -> MR.strings.tsuzuki_integration_provider_bangumi
    "shikimori" -> MR.strings.tsuzuki_integration_provider_shikimori
    "hikka" -> MR.strings.tsuzuki_integration_provider_hikka
    "anilist" -> MR.strings.tsuzuki_integration_provider_anilist
    "komga" -> MR.strings.tsuzuki_integration_provider_komga
    "kavita" -> MR.strings.tsuzuki_integration_provider_kavita
    "suwayomi" -> MR.strings.tsuzuki_integration_provider_suwayomi
    else -> null
}
