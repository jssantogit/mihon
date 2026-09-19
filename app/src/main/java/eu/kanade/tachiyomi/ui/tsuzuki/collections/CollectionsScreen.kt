package eu.kanade.tachiyomi.ui.tsuzuki.collections

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.tsuzuki.collections.CollectionsScreen as CollectionsScreenContent
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CollectionsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = metroViewModel<CollectionsScreenModel>()
        val state by screenModel.state.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        val exportLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            val ready = (state as? CollectionsScreenState.Ready)
                ?.transferState as? CollectionsTransferState.ExportReady

            if (uri == null || ready == null) {
                screenModel.clearTransferState()
                return@rememberLauncherForActivityResult
            }

            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)
                            ?.bufferedWriter()
                            ?.use { writer ->
                                writer.write(ready.json)
                            }
                            ?: error("Unable to open export destination")
                    }
                    screenModel.clearTransferState()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    screenModel.reportTransferError(
                        error.message ?: "Failed to write Collections export",
                    )
                }
            }
        }

        val importLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult

            scope.launch {
                try {
                    val json = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)
                            ?.bufferedReader()
                            ?.use { reader ->
                                reader.readText()
                            }
                            ?: error("Unable to read Collections import")
                    }
                    screenModel.importJson(json)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    screenModel.reportTransferError(
                        error.message ?: "Failed to read Collections import",
                    )
                }
            }
        }

        val exportJson = ((state as? CollectionsScreenState.Ready)
            ?.transferState as? CollectionsTransferState.ExportReady)
            ?.json

        LaunchedEffect(exportJson) {
            if (exportJson != null) {
                exportLauncher.launch(EXPORT_FILE_NAME)
            }
        }

        CollectionsScreenContent(
            state = state,
            navigateUp = navigator::pop,
            onAction = screenModel::dispatch,
            onImportRequest = {
                importLauncher.launch(IMPORT_MIME_TYPES)
            },
            onExportRequest = screenModel::prepareExport,
            onDismissTransferState = screenModel::clearTransferState,
        )
    }

    private companion object {
        const val EXPORT_FILE_NAME = "tsuzuki-collections-v1.json"
        val IMPORT_MIME_TYPES = arrayOf(
            "application/json",
            "text/json",
            "text/plain",
        )
    }
}
