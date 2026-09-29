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
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegrationConfigState
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegrationSettingsItem
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegrationsSettingsScreenModel
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegrationsSettingsState
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCategory
import tachiyomi.domain.tsuzuki.integration.model.IntegrationPolicy

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
                        item(key = "tracking_behavior") {
                            ListItem(
                                headlineContent = { Text("Comportamento de monitoramento") },
                                supportingContent = {
                                    Text("Atualizações automáticas e sincronização ao marcar capítulos como lidos")
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
                                    text = category.label(),
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
                    item {
                        ListItem(
                            headlineContent = { Text("Ativada") },
                            supportingContent = { Text("Usar ${item.label} no Tsuzuki") },
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
                            headlineContent = { Text("Tipo") },
                            supportingContent = { Text(item.category.label()) },
                        )
                    }
                    if (item.capabilities.isNotEmpty()) {
                        item {
                            ListItem(
                                headlineContent = { Text("Recursos") },
                                supportingContent = {
                                    Text("Escolha quais recursos permitidos deste provedor o Tsuzuki pode usar.")
                                },
                            )
                        }
                        items(
                            items = item.capabilities,
                            key = { it.name },
                        ) { capability ->
                            val policy = item.policies[capability]?.policy
                            ListItem(
                                headlineContent = { Text(capability.label()) },
                                supportingContent = {
                                    if (policy != null && policy != IntegrationPolicy.ALLOWED) {
                                        Text(policy.label())
                                    }
                                },
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
                    }
                    if (item.restrictedCapabilities.isNotEmpty()) {
                        item {
                            ListItem(
                                headlineContent = { Text("Recursos não habilitados") },
                                supportingContent = {
                                    Text(
                                        "Estes recursos existem tecnicamente, mas não entram no catálogo global " +
                                            "enquanto as permissões ou termos aplicáveis não estiverem resolvidos.",
                                    )
                                },
                            )
                        }
                        items(
                            items = item.restrictedCapabilities.entries.toList(),
                            key = { it.key.name },
                        ) { (capability, policy) ->
                            ListItem(
                                headlineContent = { Text(capability.label()) },
                                supportingContent = { Text(policy.policy.label()) },
                            )
                        }
                    }
                    if (
                        IntegrationCapability.TRACKING in item.capabilities &&
                        item.legacyTrackerId != null
                    ) {
                        item {
                            ListItem(
                                headlineContent = { Text("Conta e monitoramento") },
                                supportingContent = {
                                    Text("Conectar ou desconectar apenas a conta de ${item.label}")
                                },
                                modifier = Modifier.clickable {
                                    navigator.push(
                                        SettingsTsuzukiTrackingServiceScreen(item.legacyTrackerId),
                                    )
                                },
                            )
                        }
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
                        transform = IntegrationCapability::label,
                    ),
                )
                if (item.restrictedCapabilities.isNotEmpty()) {
                    Text("${item.restrictedCapabilities.size} recurso(s) bloqueado(s) por política")
                }
                Text("Configuração: ${item.configState.label()}")
                Text("Autenticação: ${item.authState.label()}")
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

private fun IntegrationCategory.label(): String = when (this) {
    IntegrationCategory.METADATA_SERVICE -> "Metadados e serviços"
    IntegrationCategory.PERSONAL_SERVER -> "Servidores pessoais"
    IntegrationCategory.COMPATIBILITY -> "Compatibilidade"
}

private fun IntegrationCapability.label(): String = when (this) {
    IntegrationCapability.SEARCH -> "Busca"
    IntegrationCapability.DISCOVERY -> "Descobrir"
    IntegrationCapability.METADATA_BASIC -> "Informações básicas"
    IntegrationCapability.METADATA_ARTWORK -> "Capas e imagens"
    IntegrationCapability.METADATA_EDITORIAL -> "Dados editoriais"
    IntegrationCapability.METADATA_STAFF -> "Autores e equipe"
    IntegrationCapability.RATINGS -> "Avaliações"
    IntegrationCapability.RELATIONS -> "Relações"
    IntegrationCapability.CROSSWALK -> "Identidade entre catálogos"
    IntegrationCapability.TRACKING -> "Monitoramento"
    IntegrationCapability.USER_LISTS -> "Listas da conta"
    IntegrationCapability.REMOTE_LIBRARY -> "Biblioteca remota"
    IntegrationCapability.READING_CONTENT -> "Conteúdo de leitura"
    IntegrationCapability.DOWNLOADS -> "Downloads"
}

private fun IntegrationPolicy.label(): String = when (this) {
    IntegrationPolicy.ALLOWED -> "Permitido"
    IntegrationPolicy.ALLOWED_WITH_ATTRIBUTION -> "Permitido com atribuição"
    IntegrationPolicy.USER_OWNED_DATA -> "Dados do seu próprio servidor"
    IntegrationPolicy.COMMERCIAL_RESTRICTION -> "Uso condicionado por licença"
    IntegrationPolicy.PERMISSION_REQUIRED -> "Permissão adicional necessária"
    IntegrationPolicy.UNVERIFIED -> "Termos ainda não verificados"
}

private fun TsuzukiIntegrationConfigState.label(): String = when (this) {
    TsuzukiIntegrationConfigState.DEFAULT -> "Padrão"
    TsuzukiIntegrationConfigState.CUSTOM -> "Personalizada"
}

private fun TsuzukiIntegrationAuthState.label(): String = when (this) {
    TsuzukiIntegrationAuthState.NOT_REQUIRED -> "Não necessária"
    TsuzukiIntegrationAuthState.MANAGED_EXTERNALLY -> "Gerenciada pela sessão existente do serviço"
}
