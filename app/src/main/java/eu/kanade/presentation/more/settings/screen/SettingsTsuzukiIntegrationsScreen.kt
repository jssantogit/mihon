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
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegraçãoAuthState
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegraçãoCapability
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegraçãoConfigState
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegraçãoSettingsItem
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegraçõesSettingsScreenModel
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegraçõesSettingsState

class SettingsTsuzukiIntegraçõesScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel =
            metroViewModel<TsuzukiIntegraçõesSettingsScreenModel>()
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
                TsuzukiIntegraçõesSettingsState.Loading -> {
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

                is TsuzukiIntegraçõesSettingsState.Loaded -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(contentPadding),
                    ) {
                        items(
                            items = current.items,
                            key = { it.id.value },
                        ) { item ->
                            IntegraçãoSettingRow(
                                item = item,
                                onAtivadaChange = {
                                    screenModel.setAtivada(
                                        id = item.id,
                                        enabled = it,
                                    )
                                },
                                onOpen = {
                                    navigator.push(SettingsTsuzukiIntegraçãoDetailScreen(item.id.value))
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

class SettingsTsuzukiIntegraçãoDetailScreen(
    private val integrationId: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = metroViewModel<TsuzukiIntegraçõesSettingsScreenModel>()
        val state by screenModel.state.collectAsStateWithLifecycle()
        val item = (state as? TsuzukiIntegraçõesSettingsState.Loaded)
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
                                        screenModel.setAtivada(item.id, it)
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
private fun IntegraçãoSettingRow(
    item: TsuzukiIntegraçãoSettingsItem,
    onAtivadaChange: (Boolean) -> Unit,
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
                        transform = TsuzukiIntegraçãoCapability::label,
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
                onCheckedChange = onAtivadaChange,
            )
        },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

private fun TsuzukiIntegraçãoCapability.label(): String = when (this) {
    TsuzukiIntegraçãoCapability.SEARCH -> "Busca"
    TsuzukiIntegraçãoCapability.DISCOVERY -> "Descobrir"
    TsuzukiIntegraçãoCapability.METADATA -> "Metadados"
    TsuzukiIntegraçãoCapability.RATINGS -> "Avaliações"
    TsuzukiIntegraçãoCapability.CHAPTER_EVIDENCE -> "Evidência de capítulos"
    TsuzukiIntegraçãoCapability.TRACKING -> "Monitoramento"
}

private fun TsuzukiIntegraçãoConfigState.label(): String = when (this) {
    TsuzukiIntegraçãoConfigState.DEFAULT -> "Padrão"
    TsuzukiIntegraçãoConfigState.CUSTOM -> "Personalizada"
}

private fun TsuzukiIntegraçãoAuthState.label(): String = when (this) {
    TsuzukiIntegraçãoAuthState.NOT_REQUIRED -> "Não necessária"
    TsuzukiIntegraçãoAuthState.MANAGED_EXTERNALLY -> "Gerenciada pela sessão existente do serviço"
}
