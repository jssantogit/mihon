package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

object SettingsTsuzukiLogsScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.tsuzuki_logs_title

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val graph = remember { context.appGraph }
        val captureSession = remember { graph.diagnosticCaptureSession }
        val crashLogUtil = remember { graph.crashLogUtil }
        val networkPreferences = remember { graph.networkPreferences }

        var captureActive by remember {
            mutableStateOf(captureSession.current() != null)
        }

        LaunchedEffect(captureActive) {
            if (captureActive) {
                while (captureSession.current() != null) {
                    delay(1_000)
                }
                captureActive = false
            }
        }

        return listOf(
            Preference.PreferenceItem.InfoPreference(
                title = stringResource(
                    if (captureActive) {
                        MR.strings.tsuzuki_logs_status_recording
                    } else {
                        MR.strings.tsuzuki_logs_status_stopped
                    },
                ),
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.tsuzuki_logs_start),
                subtitle = stringResource(MR.strings.tsuzuki_logs_start_summary),
                enabled = !captureActive,
                onClick = {
                    captureSession.start()
                    captureActive = true
                    context.toast(MR.strings.tsuzuki_logs_started)
                },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.tsuzuki_logs_stop),
                subtitle = stringResource(MR.strings.tsuzuki_logs_stop_summary),
                enabled = captureActive,
                onClick = {
                    captureSession.stop()
                    captureActive = false
                    context.toast(MR.strings.tsuzuki_logs_stopped)
                },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.tsuzuki_logs_clear),
                subtitle = stringResource(MR.strings.tsuzuki_logs_clear_summary),
                onClick = {
                    scope.launch {
                        val cleared = crashLogUtil.clearLogs()
                        captureActive = false
                        context.toast(
                            if (cleared) {
                                MR.strings.tsuzuki_logs_cleared
                            } else {
                                MR.strings.tsuzuki_logs_clear_failed
                            },
                        )
                    }
                },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.tsuzuki_logs_share),
                subtitle = stringResource(MR.strings.tsuzuki_logs_share_summary),
                onClick = {
                    scope.launch {
                        crashLogUtil.dumpLogs()
                    }
                },
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.tsuzuki_logs_options),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = networkPreferences.verboseLogging,
                        title = stringResource(MR.strings.pref_verbose_logging),
                        subtitle = stringResource(MR.strings.pref_verbose_logging_summary),
                        onValueChanged = {
                            context.toast(MR.strings.requires_app_restart)
                            true
                        },
                    ),
                ),
            ),
        )
    }
}
