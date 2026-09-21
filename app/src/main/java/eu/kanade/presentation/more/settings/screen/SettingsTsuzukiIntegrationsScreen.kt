package eu.kanade.presentation.more.settings.screen

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
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegrationAuthState
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegrationCapability
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegrationConfigState
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegrationSettingsItem
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegrationsSettingsScreenModel
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegrationsSettingsState

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
                    title = { Text("Integrations") },
                    navigationIcon = {
                        TextButton(onClick = navigator::pop) {
                            Text("Back")
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
                        items(
                            items = current.items,
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
                            )
                            HorizontalDivider()
                        }
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
) {
    ListItem(
        headlineContent = { Text(item.label) },
        supportingContent = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    item.capabilities.joinToString(
                        separator = " • ",
                        transform = TsuzukiIntegrationCapability::label,
                    ),
                )
                Text(
                    text = "Config: ${item.configState.label()}",
                )
                Text(
                    text = "Auth: ${item.authState.label()}",
                )
            }
        },
        trailingContent = {
            Switch(
                checked = item.enabled,
                onCheckedChange = onEnabledChange,
            )
        },
    )
}

private fun TsuzukiIntegrationCapability.label(): String = when (this) {
    TsuzukiIntegrationCapability.SEARCH -> "Search"
    TsuzukiIntegrationCapability.DISCOVERY -> "Discover"
    TsuzukiIntegrationCapability.METADATA -> "Metadata"
    TsuzukiIntegrationCapability.RATINGS -> "Ratings"
    TsuzukiIntegrationCapability.CHAPTER_EVIDENCE -> "Chapter evidence"
    TsuzukiIntegrationCapability.TRACKING -> "Tracking"
}

private fun TsuzukiIntegrationConfigState.label(): String = when (this) {
    TsuzukiIntegrationConfigState.DEFAULT -> "Default"
    TsuzukiIntegrationConfigState.CUSTOM -> "Custom"
}

private fun TsuzukiIntegrationAuthState.label(): String = when (this) {
    TsuzukiIntegrationAuthState.NOT_REQUIRED -> "Not required"
    TsuzukiIntegrationAuthState.MANAGED_EXTERNALLY -> "Managed by existing service session"
}
