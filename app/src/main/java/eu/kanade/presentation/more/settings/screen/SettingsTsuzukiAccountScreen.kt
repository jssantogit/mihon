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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.tsuzuki.account.TsuzukiAccountScreenError
import eu.kanade.tachiyomi.ui.tsuzuki.account.TsuzukiAccountScreenModel
import tachiyomi.domain.tsuzuki.account.model.AccountState
import tachiyomi.presentation.core.components.material.Scaffold

object SettingsTsuzukiAccountScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = metroViewModel<TsuzukiAccountScreenModel>()
        val state by screenModel.state.collectAsStateWithLifecycle()

        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }

        Scaffold(
            topBar = {
                AppBar(
                    titleContent = { AppBarTitle("Account") },
                    navigateUp = navigator::pop,
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
                    Text(
                        text = when (val account = state.accountState) {
                            AccountState.LoggedOut ->
                                "Cloud sync is off. Tsuzuki remains fully usable with local data only."
                            is AccountState.Authenticated ->
                                "Signed in as ${account.account.email}"
                            is AccountState.EmailConfirmationRequired ->
                                "Check ${account.email} to confirm your account."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                when (state.accountState) {
                    AccountState.LoggedOut,
                    is AccountState.EmailConfirmationRequired,
                    -> {
                        item {
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth(),
                                value = email,
                                onValueChange = {
                                    email = it
                                    screenModel.clearFeedback()
                                },
                                label = { Text("Email") },
                                singleLine = true,
                                enabled = !state.isWorking,
                            )
                        }
                        item {
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth(),
                                value = password,
                                onValueChange = {
                                    password = it
                                    screenModel.clearFeedback()
                                },
                                label = { Text("Password") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                enabled = !state.isWorking,
                            )
                        }
                        item {
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !state.isWorking,
                                onClick = { screenModel.login(email, password) },
                            ) {
                                Text("Log in")
                            }
                        }
                        item {
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !state.isWorking,
                                onClick = { screenModel.register(email, password) },
                            ) {
                                Text("Create account")
                            }
                        }
                        item {
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !state.isWorking,
                                onClick = { screenModel.sendPasswordRecovery(email) },
                            ) {
                                Text("Reset password")
                            }
                        }
                    }

                    is AccountState.Authenticated -> {
                        item {
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !state.isWorking,
                                onClick = screenModel::logout,
                            ) {
                                Text("Log out")
                            }
                        }
                    }
                }

                state.error?.let { error ->
                    item {
                        Text(
                            text = error.messageText(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                state.recoverySentTo?.let { address ->
                    item {
                        Text(
                            text = "Password recovery sent to $address",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

private fun TsuzukiAccountScreenError.messageText(): String = when (this) {
    TsuzukiAccountScreenError.INVALID_INPUT ->
        "Enter a valid email address and a non-empty password."
    TsuzukiAccountScreenError.AUTHENTICATION_FAILED ->
        "The account request was rejected. Check your email and password."
    TsuzukiAccountScreenError.NETWORK_UNAVAILABLE ->
        "Network is unavailable. Your local data is unaffected."
    TsuzukiAccountScreenError.CONFIGURATION_UNAVAILABLE ->
        "Cloud sync is not configured in this build."
    TsuzukiAccountScreenError.UNKNOWN ->
        "Account request failed."
}
