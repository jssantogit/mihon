package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.tsuzuki.sync.SyncConflictResolutionScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.tsuzuki.sync.TsuzukiSyncScreenError
import eu.kanade.tachiyomi.ui.tsuzuki.sync.TsuzukiSyncScreenModel
import tachiyomi.domain.tsuzuki.account.model.AccountState
import tachiyomi.domain.tsuzuki.sync.service.SyncRuntimeState
import tachiyomi.presentation.core.components.material.Scaffold

object SettingsTsuzukiSyncScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = metroViewModel<TsuzukiSyncScreenModel>()
        val state by screenModel.state.collectAsStateWithLifecycle()
        val running = state.runtimeState is SyncRuntimeState.Running

        Scaffold(
            topBar = {
                AppBar(
                    titleContent = { AppBarTitle("Sync") },
                    navigateUp = { navigator.pop() },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = if (state.cloudEnabled) {
                                "Cloud sync enabled"
                            } else {
                                "Cloud sync off"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = when (val account = state.accountState) {
                                AccountState.LoggedOut ->
                                    "Sign in under Account to enable cloud sync. Local data remains available."
                                is AccountState.Authenticated ->
                                    account.account.email
                                is AccountState.EmailConfirmationRequired ->
                                    "Confirm ${account.email} before cloud sync can start."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item {
                    Text(
                        text = "Pending mutations: ${state.pendingMutationCount}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                item {
                    Text(
                        text = "Unresolved conflicts: ${state.unresolvedConflictCount}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                item {
                    Text(
                        text = "Last successful sync: " +
                            (state.lastSuccessfulSyncAtEpochMillis?.toString() ?: "Never"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                item {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.cloudEnabled && !running && !state.isLoadingDiagnostics,
                        onClick = screenModel::syncNow,
                    ) {
                        Text(if (running) "Syncing…" else "Sync Now")
                    }
                }

                item {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isLoadingDiagnostics && !running,
                        onClick = screenModel::refreshDiagnostics,
                    ) {
                        Text("Refresh diagnostics")
                    }
                }

                state.error?.let { error ->
                    item {
                        Text(
                            text = error.messageText(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                if (state.conflicts.isNotEmpty()) {
                    item {
                        Text(
                            text = "Resolve conflicts",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    item {
                        SyncConflictResolutionScreen(
                            conflicts = state.conflicts,
                            resolvingConflictKey = state.resolvingConflictKey,
                            onResolve = screenModel::resolveConflict,
                        )
                    }
                }
            }
        }
    }
}

private fun TsuzukiSyncScreenError.messageText(): String = when (this) {
    TsuzukiSyncScreenError.AUTHORIZATION_REQUIRED ->
        "Sign in to use cloud sync."
    TsuzukiSyncScreenError.NETWORK_UNAVAILABLE ->
        "Sync could not reach the server. Local changes remain queued."
    TsuzukiSyncScreenError.REMOTE_UNAVAILABLE ->
        "The cloud backend is temporarily unavailable."
    TsuzukiSyncScreenError.CONFLICT_REMAINS ->
        "A conflict still needs an explicit choice."
    TsuzukiSyncScreenError.UNKNOWN ->
        "Sync failed. Local data remains intact."
}
