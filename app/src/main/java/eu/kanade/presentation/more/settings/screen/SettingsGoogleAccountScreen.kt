package eu.kanade.presentation.more.settings.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.tsuzuki.googleauth.GoogleAuthConnectResult
import eu.kanade.tachiyomi.data.tsuzuki.googleauth.GoogleAuthorizationOperationResult
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAuthState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

object SettingsGoogleAccountScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_google_account

    @Composable
    override fun RowScope.AppBarAction() = Unit

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val coordinator = remember { context.appGraph.googleAuthInteractiveCoordinator }
        val scope = rememberCoroutineScope()
        val state by coordinator.state.collectAsState()

        var pendingRequest by remember { mutableStateOf<IntentSenderRequest?>(null) }
        var accountSelectionRequested by remember { mutableStateOf(false) }

        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult(),
        ) { activityResult ->
            scope.launch {
                when (val result = coordinator.complete(activityResult)) {
                    GoogleAuthConnectResult.Completed,
                    GoogleAuthConnectResult.InProgress,
                    -> Unit
                    GoogleAuthConnectResult.AccountSelectionRequired -> {
                        accountSelectionRequested = true
                    }
                    is GoogleAuthConnectResult.UserActionRequired -> {
                        pendingRequest = coordinator.createRequest(result.action)
                    }
                }
            }
        }

        val accountLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { activityResult ->
            scope.launch {
                when (val result = coordinator.completeAccountSelection(activityResult)) {
                    GoogleAuthConnectResult.Completed,
                    GoogleAuthConnectResult.InProgress,
                    -> Unit
                    GoogleAuthConnectResult.AccountSelectionRequired -> {
                        accountSelectionRequested = true
                    }
                    is GoogleAuthConnectResult.UserActionRequired -> {
                        pendingRequest = coordinator.createRequest(result.action)
                    }
                }
            }
        }

        LaunchedEffect(accountSelectionRequested) {
            if (!accountSelectionRequested) {
                return@LaunchedEffect
            }
            accountSelectionRequested = false

            try {
                accountLauncher.launch(coordinator.createAccountSelectionRequest())
            } catch (_: Exception) {
                coordinator.cancelPending()
                context.toast(MR.strings.google_auth_launch_failed)
            }
        }

        LaunchedEffect(pendingRequest) {
            val request = pendingRequest ?: return@LaunchedEffect
            pendingRequest = null

            try {
                launcher.launch(request)
            } catch (_: Exception) {
                coordinator.cancelPending()
                context.toast(MR.strings.google_auth_launch_failed)
            }
        }

        val statusTitle = when (state) {
            GoogleAuthState.Restoring -> stringResource(MR.strings.google_auth_restoring)
            GoogleAuthState.SignedOut -> stringResource(MR.strings.google_auth_signed_out)
            GoogleAuthState.Connecting -> stringResource(MR.strings.google_auth_connecting)
            is GoogleAuthState.Connected -> stringResource(MR.strings.google_auth_connected)
            is GoogleAuthState.AuthorizationRequired -> stringResource(MR.strings.google_auth_authorization_required)
            is GoogleAuthState.RecoverableFailure -> stringResource(MR.strings.google_auth_temporarily_unavailable)
            is GoogleAuthState.Error -> stringResource(MR.strings.google_auth_error)
        }

        val statusSubtitle = when (val current = state) {
            is GoogleAuthState.Connected -> current.account.accountName
            is GoogleAuthState.AuthorizationRequired -> current.account?.accountName
            is GoogleAuthState.RecoverableFailure -> current.failure.message ?: current.account?.accountName
            is GoogleAuthState.Error -> current.failure.message
            GoogleAuthState.Restoring,
            GoogleAuthState.SignedOut,
            GoogleAuthState.Connecting,
            -> null
        }

        val action = when (state) {
            GoogleAuthState.Restoring,
            GoogleAuthState.Connecting,
            -> Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.google_auth_wait),
                enabled = false,
            )

            is GoogleAuthState.Connected -> Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.google_auth_disconnect),
                onClick = {
                    scope.launch {
                        when (coordinator.disconnect()) {
                            GoogleAuthorizationOperationResult.Success -> Unit
                            is GoogleAuthorizationOperationResult.RecoverableFailure,
                            is GoogleAuthorizationOperationResult.Failure,
                            -> context.toast(MR.strings.google_auth_remote_disconnect_failed)
                        }
                    }
                },
            )

            GoogleAuthState.SignedOut -> connectPreference(
                title = stringResource(MR.strings.google_auth_connect),
                coordinator = coordinator,
                onUserActionRequired = { pendingRequest = it },
                onAccountSelectionRequired = { accountSelectionRequested = true },
                scope = scope,
            )

            is GoogleAuthState.AuthorizationRequired -> connectPreference(
                title = stringResource(MR.strings.google_auth_reconnect),
                coordinator = coordinator,
                onUserActionRequired = { pendingRequest = it },
                onAccountSelectionRequired = { accountSelectionRequested = true },
                scope = scope,
            )

            is GoogleAuthState.RecoverableFailure,
            is GoogleAuthState.Error,
            -> connectPreference(
                title = stringResource(MR.strings.action_retry),
                coordinator = coordinator,
                onUserActionRequired = { pendingRequest = it },
                onAccountSelectionRequired = { accountSelectionRequested = true },
                scope = scope,
            )
        }

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.google_auth_status),
                preferenceItems = listOf(
                    Preference.PreferenceItem.TextPreference(
                        title = statusTitle,
                        subtitle = statusSubtitle,
                        enabled = false,
                    ),
                    action,
                ),
            ),
            Preference.PreferenceItem.InfoPreference(
                stringResource(MR.strings.google_auth_scope_info),
            ),
        )
    }

    @Composable
    private fun connectPreference(
        title: String,
        coordinator: eu.kanade.tachiyomi.data.tsuzuki.googleauth.GoogleAuthInteractiveCoordinator,
        onUserActionRequired: (IntentSenderRequest) -> Unit,
        onAccountSelectionRequired: () -> Unit,
        scope: kotlinx.coroutines.CoroutineScope,
    ): Preference.PreferenceItem.TextPreference {
        return Preference.PreferenceItem.TextPreference(
            title = title,
            onClick = {
                scope.launch {
                    when (val result = coordinator.beginConnect()) {
                        GoogleAuthConnectResult.Completed,
                        GoogleAuthConnectResult.InProgress,
                        -> Unit
                        GoogleAuthConnectResult.AccountSelectionRequired -> {
                            onAccountSelectionRequired()
                        }
                        is GoogleAuthConnectResult.UserActionRequired -> {
                            onUserActionRequired(coordinator.createRequest(result.action))
                        }
                    }
                }
            },
        )
    }
}
