package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.BugReport

object SettingsTsuzukiAdvancedHubScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Avançado") },
                    navigationIcon = {
                        TextButton(onClick = navigator::pop) { Text("Voltar") }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                ListItem(
                    headlineContent = { Text("Logs") },
                    leadingContent = {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.BugReport,
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.clickable { navigator.push(SettingsTsuzukiLogsScreen) },
                )
                ListItem(
                    headlineContent = { Text("Segurança e Privacidade") },
                    modifier = Modifier.clickable { navigator.push(SettingsSecurityScreen) },
                )
                ListItem(
                    headlineContent = { Text("Dados e Armazenamento") },
                    modifier = Modifier.clickable { navigator.push(SettingsDataScreen) },
                )
                ListItem(
                    headlineContent = { Text("Opções avançadas") },
                    modifier = Modifier.clickable { navigator.push(SettingsAdvancedScreen) },
                )
            }
        }
    }
}
