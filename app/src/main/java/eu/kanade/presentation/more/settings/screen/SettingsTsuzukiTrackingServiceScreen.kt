package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.track.components.TrackLogoIcon
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.bangumi.Bangumi
import eu.kanade.tachiyomi.data.track.hikka.Hikka
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.data.track.shikimori.Shikimori
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

object SettingsTsuzukiTrackingBehaviorScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_tracking

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val trackPreferences = remember { context.appGraph.trackPreferences }
        return listOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = trackPreferences.autoUpdateTrack,
                title = stringResource(MR.strings.pref_auto_update_manga_sync),
            ),
            Preference.PreferenceItem.ListPreference(
                preference = trackPreferences.autoUpdateTrackOnMarkRead,
                entries = AutoTrackState.entries
                    .associateWith { stringResource(it.titleRes) },
                title = stringResource(MR.strings.pref_auto_update_manga_on_mark_read),
            ),
        )
    }
}

class SettingsTsuzukiTrackingServiceScreen(
    private val trackerId: Long,
) : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val tracker = remember(trackerId) { context.appGraph.trackerManager.get(trackerId) }
        val sourceManager = remember { context.appGraph.sourceManager }
        val acceptedSources by produceState(
            initialValue = emptyList<Source>(),
            key1 = tracker,
        ) {
            value = if (tracker is EnhancedTracker) {
                val acceptedClasses = tracker.getAcceptedSources().toSet()
                sourceManager.getAll()
                    .filter { source -> source::class.qualifiedName in acceptedClasses }
                    .sortedWith(compareBy(Source::name, Source::lang, Source::id))
            } else {
                emptyList()
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(tracker?.name ?: stringResource(MR.strings.tsuzuki_tracking_account)) },
                    navigationIcon = {
                        TextButton(onClick = navigator::pop) {
                            Text(stringResource(MR.strings.tsuzuki_navigation_back))
                        }
                    },
                )
            },
        ) { contentPadding ->
            if (tracker == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(stringResource(MR.strings.tsuzuki_tracking_service_unavailable))
                }
                return@Scaffold
            }

            TrackingServiceContent(
                tracker = tracker,
                acceptedSources = acceptedSources,
                onConfigureSource = { source ->
                    navigator.push(SourcePreferencesScreen(source.id))
                },
                onOpenExtensions = {
                    navigator.push(SettingsTsuzukiAddonsScreen)
                },
                modifier = Modifier.padding(contentPadding),
            )
        }
    }
}

@Composable
private fun TrackingServiceContent(
    tracker: Tracker,
    acceptedSources: List<Source>,
    onConfigureSource: (Source) -> Unit,
    onOpenExtensions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isLoggedIn by tracker.isLoggedInFlow.collectAsState(initial = tracker.isLoggedIn)
    var loginDialog by remember { mutableStateOf(false) }
    var logoutDialog by remember { mutableStateOf(false) }

    if (loginDialog) {
        TrackerCredentialLoginDialog(
            tracker = tracker,
            usernameLabel = if (tracker.id == KITSU_TRACKER_ID) MR.strings.email else MR.strings.username,
            tokenOnly = tracker is Bangumi,
            onDismiss = { loginDialog = false },
        )
    }
    if (logoutDialog) {
        AlertDialog(
            onDismissRequest = { logoutDialog = false },
            title = { Text(stringResource(MR.strings.tsuzuki_tracking_disconnect_confirm, tracker.name)) },
            text = { Text(stringResource(MR.strings.tsuzuki_tracking_disconnect_summary)) },
            confirmButton = {
                Button(
                    onClick = {
                        tracker.logout()
                        scope.launch {
                            context.appGraph.refreshUserLibraries.clearForLegacyTracker(tracker.id)
                        }
                        logoutDialog = false
                        context.toast(MR.strings.logout_success)
                    },
                ) {
                    Text(stringResource(MR.strings.tsuzuki_tracking_disconnect))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { logoutDialog = false }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            ListItem(
                headlineContent = { Text(stringResource(MR.strings.tsuzuki_tracking_account)) },
                leadingContent = { TrackLogoIcon(tracker) },
                supportingContent = {
                    Text(
                        if (isLoggedIn) {
                            tracker.getDisplayUsername().ifBlank {
                                stringResource(MR.strings.tsuzuki_tracking_connected)
                            }
                        } else {
                            stringResource(MR.strings.tsuzuki_tracking_not_connected)
                        },
                    )
                },
            )
        }
        item {
            ListItem(
                headlineContent = {
                    Text(
                        stringResource(
                            if (isLoggedIn) {
                                MR.strings.tsuzuki_tracking_disconnect
                            } else {
                                MR.strings.tsuzuki_tracking_connect
                            },
                        ),
                    )
                },
                supportingContent = {
                    Text(stringResource(loginDescriptionRes(tracker)))
                },
            )
            val canStartLogin = when (tracker) {
                is MyAnimeList -> tracker.hasClientId()
                is Shikimori -> tracker.hasApplicationCredentials()
                is Hikka -> tracker.hasApplicationCredentials()
                else -> tracker !is EnhancedTracker || acceptedSources.isNotEmpty()
            }
            Button(
                enabled = isLoggedIn || canStartLogin,
                onClick = {
                    if (isLoggedIn) {
                        logoutDialog = true
                    } else {
                        when (tracker.id) {
                            MAL_TRACKER_ID -> context.openInBrowser(
                                (tracker as MyAnimeList).authUrl(),
                                forceDefaultBrowser = true,
                            )
                            SHIKIMORI_TRACKER_ID -> context.openInBrowser(
                                (tracker as Shikimori).authUrl(),
                                forceDefaultBrowser = true,
                            )
                            BANGUMI_TRACKER_ID -> loginDialog = true
                            HIKKA_TRACKER_ID -> context.openInBrowser(
                                (tracker as Hikka).authUrl(),
                                forceDefaultBrowser = true,
                            )
                            else -> {
                                if (tracker is EnhancedTracker) {
                                    tracker.loginNoop()
                                } else {
                                    loginDialog = true
                                }
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    stringResource(
                        if (isLoggedIn) {
                            MR.strings.tsuzuki_tracking_disconnect
                        } else {
                            MR.strings.tsuzuki_tracking_connect
                        },
                    ),
                )
            }
        }
        if (tracker is EnhancedTracker) {
            if (acceptedSources.isEmpty()) {
                item(key = "accepted_source_missing") {
                    ListItem(
                        headlineContent = {
                            Text(stringResource(MR.strings.tsuzuki_tracking_source_missing))
                        },
                        supportingContent = {
                            Text(
                                stringResource(
                                    MR.strings.tsuzuki_tracking_source_missing_summary,
                                    tracker.name,
                                ),
                            )
                        },
                        trailingContent = {
                            TextButton(onClick = onOpenExtensions) {
                                Text(stringResource(MR.strings.label_extensions))
                            }
                        },
                    )
                }
            } else {
                item {
                    ListItem(
                        headlineContent = {
                            Text(stringResource(MR.strings.tsuzuki_tracking_server_configuration))
                        },
                        supportingContent = {
                            Text(stringResource(MR.strings.tsuzuki_tracking_server_configuration_summary))
                        },
                    )
                }
                acceptedSources.forEach { source ->
                    item(key = "accepted_source_${source.id}") {
                        ListItem(
                            headlineContent = { Text(source.name) },
                            supportingContent = {
                                Text(stringResource(MR.strings.tsuzuki_tracking_source_configuration_summary))
                            },
                            trailingContent = {
                                TextButton(onClick = { onConfigureSource(source) }) {
                                    Text(stringResource(MR.strings.tsuzuki_tracking_configure_source))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackerCredentialLoginDialog(
    tracker: Tracker,
    usernameLabel: StringResource,
    tokenOnly: Boolean = false,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loginFailedMessage = stringResource(MR.strings.tsuzuki_tracking_login_failed)
    var username by remember { mutableStateOf(if (tokenOnly) "" else tracker.getUsername()) }
    var password by remember { mutableStateOf(if (tokenOnly) "" else tracker.getPassword()) }
    var processing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.tsuzuki_tracking_connect) + " " + tracker.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!tokenOnly) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(usernameLabel)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = {
                        Text(
                            stringResource(
                                if (tokenOnly) {
                                    MR.strings.tsuzuki_bangumi_access_token
                                } else {
                                    MR.strings.password
                                },
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                errorMessage?.let { Text(it) }
                if (processing) {
                    CircularProgressIndicator()
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !processing &&
                    password.isNotBlank() &&
                    (tokenOnly || username.isNotBlank()),
                onClick = {
                    scope.launch {
                        processing = true
                        errorMessage = null
                        val error = try {
                            withContext(Dispatchers.IO) {
                                tracker.login(if (tokenOnly) "" else username, password)
                                context.appGraph.refreshUserLibraries.refreshForLegacyTracker(tracker.id)
                            }
                            null
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            error
                        }
                        processing = false
                        if (error == null) {
                            context.toast(MR.strings.login_success)
                            onDismiss()
                        } else {
                            tracker.logout()
                            context.appGraph.refreshUserLibraries.clearForLegacyTracker(tracker.id)
                            errorMessage = error.message ?: loginFailedMessage
                        }
                    }
                },
            ) {
                Text(stringResource(MR.strings.tsuzuki_tracking_connect))
            }
        },
        dismissButton = {
            OutlinedButton(
                enabled = !processing,
                onClick = onDismiss,
            ) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

private fun loginDescriptionRes(tracker: Tracker): StringResource = when {
    tracker is MyAnimeList && !tracker.hasClientId() -> MR.strings.tsuzuki_mal_client_id_required
    tracker is MyAnimeList -> MR.strings.tsuzuki_mal_tracking_login_setup
    tracker is Shikimori && !tracker.hasApplicationCredentials() ->
        MR.strings.tsuzuki_shikimori_credentials_required
    tracker is Hikka && !tracker.hasApplicationCredentials() ->
        MR.strings.tsuzuki_hikka_credentials_required
    tracker is Bangumi -> MR.strings.tsuzuki_bangumi_access_token_summary
    tracker.id == MAL_TRACKER_ID ||
        tracker.id == SHIKIMORI_TRACKER_ID ||
        tracker.id == HIKKA_TRACKER_ID -> MR.strings.tsuzuki_tracking_login_browser

    tracker.id == KITSU_TRACKER_ID -> MR.strings.tsuzuki_tracking_login_kitsu
    tracker.id == MANGAUPDATES_TRACKER_ID -> MR.strings.tsuzuki_tracking_login_mangaupdates
    else -> MR.strings.tsuzuki_tracking_login_local
}

private const val MAL_TRACKER_ID = 1L
private const val KITSU_TRACKER_ID = 3L
private const val SHIKIMORI_TRACKER_ID = 4L
private const val BANGUMI_TRACKER_ID = 5L
private const val MANGAUPDATES_TRACKER_ID = 7L
private const val HIKKA_TRACKER_ID = 10L
