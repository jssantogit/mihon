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
                    title = { Text("Integrações") },
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
                    item {
                        ListItem(
                            headlineContent = { Text("Ativada") },
                            supportingContent = { Text("Usar ${item.label} como provedor no Tsuzuki") },
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
                    item {
                        ListItem(
                            headlineContent = { Text("Recursos") },
                            supportingContent = {
                                Text("Escolha quais recursos deste provedor o Tsuzuki pode usar.")
                            },
                        )
                    }
                    items(
                        items = item.capabilities,
                        key = { it.name },
                    ) { capability ->
                        ListItem(
                            headlineContent = { Text(capability.label()) },
                            trailingContent = {
                                Switch(
                                    checked = item.capabilityEnabled[capability] ?: true,
                                    onCheckedChange = {
                                        screenModel.setCapabilityEnabled(item.id, capability, it)
                                    },
                                )
                            },
                        )
                    }
                    item {
                        ListItem(
                            headlineContent = { Text("Configuração") },
                            supportingContent = { Text(item.configState.label()) },
                        )
                    }
                    item {
                        ListItem(
                            headlineContent = { Text("Autenticação") },
                            supportingContent = { Text(item.authState.label()) },
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
                Text(
                    item.capabilities.joinToString(
                        separator = " • ",
                        transform = TsuzukiIntegrationCapability::label,
                    ),
                )
                Text(
                    text = "Configuração: ${item.configState.label()}",
                )
                Text(
                    text = "Autenticação: ${item.authState.label()}",
                )
            }
        },
        trailingContent = {
            Switch(
                checked = item.enabled,
                onCheckedChange = onEnabledChange,
            )
        },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

private fun TsuzukiIntegrationCapability.label(): String = when (this) {
    TsuzukiIntegrationCapability.SEARCH -> "Busca"
    TsuzukiIntegrationCapability.DISCOVERY -> "Descobrir"
    TsuzukiIntegrationCapability.METADATA -> "Metadados"
    TsuzukiIntegrationCapability.RATINGS -> "Avaliações"
    TsuzukiIntegrationCapability.CHAPTER_EVIDENCE -> "Evidência de capítulos"
    TsuzukiIntegrationCapability.TRACKING -> "Monitoramento"
}

private fun TsuzukiIntegrationConfigState.label(): String = when (this) {
    TsuzukiIntegrationConfigState.DEFAULT -> "Padrão"
    TsuzukiIntegrationConfigState.CUSTOM -> "Personalizada"
}

private fun TsuzukiIntegrationAuthState.label(): String = when (this) {
    TsuzukiIntegrationAuthState.NOT_REQUIRED -> "Não necessária"
    TsuzukiIntegrationAuthState.MANAGED_EXTERNALLY -> "Gerenciada pela sessão existente do serviço"
}
