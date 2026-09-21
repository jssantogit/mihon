package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.tsuzuki.collections.CollectionsScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionsScreenModel
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionsScreenState
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionsTransferState

object SettingsTsuzukiHomeScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = metroViewModel<CollectionsScreenModel>()
        val state by screenModel.state.collectAsStateWithLifecycle()
        var importing by remember { mutableStateOf(false) }
        var importJson by remember { mutableStateOf("") }

        CollectionsScreen(
            state = state,
            navigateUp = { navigator.pop() },
            onAction = screenModel::dispatch,
            onImportRequest = {
                importJson = ""
                importing = true
            },
            onExportRequest = screenModel::prepareExport,
            onDismissTransferState = screenModel::clearTransferState,
        )

        if (importing) {
            AlertDialog(
                onDismissRequest = { importing = false },
                confirmButton = {
                    TextButton(
                        enabled = importJson.isNotBlank(),
                        onClick = {
                            screenModel.importJson(importJson)
                            importing = false
                        },
                    ) {
                        Text("Import")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { importing = false }) {
                        Text("Cancel")
                    }
                },
                title = { Text("Import Collections") },
                text = {
                    OutlinedTextField(
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                        value = importJson,
                        onValueChange = { importJson = it },
                        label = { Text("Collections JSON") },
                        minLines = 8,
                    )
                },
            )
        }

        val ready = state as? CollectionsScreenState.Ready
        val export = ready?.transferState as? CollectionsTransferState.ExportReady
        if (export != null) {
            AlertDialog(
                onDismissRequest = screenModel::clearTransferState,
                confirmButton = {
                    TextButton(onClick = screenModel::clearTransferState) {
                        Text("Done")
                    }
                },
                title = { Text("Export Collections") },
                text = {
                    Column {
                        Text("Copy or save this JSON. It contains Collection definitions only.")
                        SelectionContainer {
                            Text(export.json)
                        }
                    }
                },
            )
        }
    }
}
