package eu.kanade.tachiyomi.ui.tsuzuki.addon

import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.browse.ExtensionScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsViewModel
import eu.kanade.tachiyomi.ui.browse.extension.details.ExtensionDetailsScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import tachiyomi.presentation.core.components.material.Scaffold

class TsuzukiAvailableAddonsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<ExtensionsViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Available Add-ons") },
                    navigationIcon = {
                        TextButton(onClick = navigator::pop) {
                            Text("Back")
                        }
                    },
                    actions = {
                        TextButton(onClick = viewModel::findAvailableExtensions) {
                            Text("Refresh")
                        }
                    },
                )
            },
        ) { contentPadding ->
            ExtensionScreen(
                state = state,
                contentPadding = contentPadding,
                searchQuery = state.searchQuery,
                onLongClickItem = {},
                onClickItemCancel = viewModel::cancelInstallUpdateExtension,
                onClickUpdateAll = viewModel::updateAllExtensions,
                onOpenWebView = { extension ->
                    extension.sources.firstOrNull()?.let { source ->
                        navigator.push(
                            WebViewScreen(
                                url = source.baseUrl,
                                initialTitle = source.name,
                                sourceId = source.id,
                            ),
                        )
                    }
                },
                onInstallExtension = viewModel::installExtension,
                onOpenExtension = { extension ->
                    navigator.push(ExtensionDetailsScreen(extension.pkgName))
                },
                onTrustExtension = viewModel::trustExtension,
                onUninstallExtension = viewModel::uninstallExtension,
                onUpdateExtension = viewModel::updateExtension,
                onRefresh = viewModel::findAvailableExtensions,
            )
        }
    }
}
