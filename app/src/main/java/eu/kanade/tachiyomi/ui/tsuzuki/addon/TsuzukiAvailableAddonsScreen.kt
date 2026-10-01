package eu.kanade.tachiyomi.ui.tsuzuki.addon

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.extension.model.Extension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

class TsuzukiAvailableAddonsScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val extensionManager = remember { context.appGraph.extensionManager }
        val available by extensionManager.availableExtensionsFlow.collectAsStateWithLifecycle()
        val installed by extensionManager.installedExtensionsFlow.collectAsStateWithLifecycle(
            initialValue = emptyList(),
        )
        var refreshing by remember { mutableStateOf(false) }
        var installingPackages by remember { mutableStateOf(emptySet<String>()) }

        suspend fun refresh() {
            refreshing = true
            withContext(Dispatchers.IO) {
                extensionManager.findAvailableExtensions()
            }
            refreshing = false
        }

        LaunchedEffect(Unit) {
            refresh()
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(MR.strings.tsuzuki_addons_title)) },
                    navigationIcon = {
                        TextButton(onClick = navigator::pop) {
                            Text(stringResource(MR.strings.tsuzuki_navigation_back))
                        }
                    },
                    actions = {
                        TextButton(
                            enabled = !refreshing,
                            onClick = { scope.launch { refresh() } },
                        ) {
                            Text(if (refreshing) "…" else "Refresh")
                        }
                    },
                )
            },
        ) { contentPadding ->
            val installedPackages = installed.mapTo(mutableSetOf(), Extension.Installed::pkgName)
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                if (available.isEmpty() && refreshing) {
                    item(key = "loading") {
                        ListItem(
                            headlineContent = { Text(stringResource(MR.strings.tsuzuki_addons_title)) },
                            trailingContent = { CircularProgressIndicator() },
                        )
                    }
                }

                items(
                    items = available.sortedWith(compareBy(Extension.Available::lang, Extension.Available::name)),
                    key = Extension.Available::pkgName,
                ) { extension ->
                    val isInstalled = extension.pkgName in installedPackages
                    val isInstalling = extension.pkgName in installingPackages
                    ListItem(
                        headlineContent = { Text(extension.name) },
                        supportingContent = { Text(extension.lang) },
                        trailingContent = {
                            when {
                                isInstalled -> Text(stringResource(MR.strings.ext_installed))
                                isInstalling -> CircularProgressIndicator()
                                else -> {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                installingPackages += extension.pkgName
                                                try {
                                                    extensionManager.installExtension(extension).collect { }
                                                } finally {
                                                    installingPackages -= extension.pkgName
                                                }
                                            }
                                        },
                                    ) {
                                        Text(stringResource(MR.strings.ext_install))
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
