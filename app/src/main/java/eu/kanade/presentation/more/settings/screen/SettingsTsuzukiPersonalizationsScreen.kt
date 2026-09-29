package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionsScreen

object SettingsTsuzukiPersonalizationsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Personalizações") },
                    navigationIcon = {
                        TextButton(onClick = navigator::pop) { Text("Voltar") }
                    },
                )
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                ListItem(
                    headlineContent = { Text("Aparência") },
                    supportingContent = { Text("Tema, idioma visual, data e hora") },
                    modifier = Modifier.clickable { navigator.push(SettingsAppearanceScreen) },
                )
                ListItem(
                    headlineContent = { Text("Collections") },
                    supportingContent = {
                        Text("Organize coleções, pastas e listas exibidas pelo Tsuzuki")
                    },
                    modifier = Modifier.clickable { navigator.push(CollectionsScreen()) },
                )
            }
        }
    }
}
