package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoresScreen
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.ui.browse.extension.details.ExtensionDetailsScreen
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import tachiyomi.domain.tsuzuki.addon.model.AddonSyncIntent
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

object SettingsTsuzukiAddonsScreen : SearchableSettings {

    @Composable
    @ReadOnlyComposable
    override fun getTitleRes() = MR.strings.tsuzuki_addons_title

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val repository = remember { context.appGraph.addonRepository }
        val intentRepository = remember { context.appGraph.addonSyncIntentRepository }
        val addons by repository.observeInstalled().collectAsState(initial = emptyList())
        val syncIntent by intentRepository.observe().collectAsState(initial = AddonSyncIntent())
        val scope = rememberCoroutineScope()

        val installedIds = addons.map { it.id.value }.toSet()
        val installedItems: List<Preference.PreferenceItem<out Any, out Any>> = addons
            .sortedBy { it.displayName.lowercase() }
            .map { addon ->
                Preference.PreferenceItem.CustomPreference(
                    title = addon.displayName,
                ) {
                    AddonPreferenceRow(
                        addon = addon,
                        onEnabledChange = { enabled ->
                            scope.launch {
                                intentRepository.recordEnabled(addon.id, enabled)
                                repository.setEnabled(addon.id, enabled)
                            }
                        },
                        onOpenSettings = {
                            navigator.push(ExtensionDetailsScreen(addon.id.value))
                        },
                        onUninstall = {
                            scope.launch {
                                repository.uninstall(addon.id)
                                intentRepository.removeDesired(addon.id)
                            }
                        },
                    )
                }
            }
        val missingItems: List<Preference.PreferenceItem<out Any, out Any>> =
            (syncIntent.desiredPackageIds - installedIds)
                .sorted()
                .map { packageId ->
                    Preference.PreferenceItem.TextPreference(
                        title = packageId,
                        subtitle = "Needs installation · Install manually from Extension Stores",
                    )
                }
        val addonItems = (installedItems + missingItems).ifEmpty {
            listOf(
                Preference.PreferenceItem.InfoPreference(
                    title = stringResource(MR.strings.tsuzuki_addons_none_installed),
                ),
            )
        }

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.tsuzuki_addons_repositories),
                preferenceItems = listOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.extensionStores),
                        onClick = { navigator.push(ExtensionStoresScreen()) },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.tsuzuki_addons_installed),
                preferenceItems = addonItems,
            ),
        )
    }
}

@Composable
private fun AddonPreferenceRow(
    addon: InstalledAddon,
    onEnabledChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onUninstall: () -> Unit,
) {
    var confirmUninstall by remember(addon.id) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        TextPreferenceWidget(
            title = addon.displayName,
            subtitle = buildString {
                append(addon.versionName)
                append(" · ")
                append(
                    stringResource(
                        if (addon.hasUpdate) {
                            MR.strings.tsuzuki_addon_update_available
                        } else if (addon.enabled) {
                            MR.strings.tsuzuki_addon_enabled
                        } else {
                            MR.strings.tsuzuki_addon_disabled
                        },
                    ),
                )
            },
            widget = {
                Switch(
                    checked = addon.enabled,
                    onCheckedChange = onEnabledChange,
                )
            },
            onPreferenceClick = onOpenSettings.takeIf { addon.hasSettings },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (addon.hasSettings) {
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(MR.strings.label_settings))
                }
            }
            TextButton(onClick = { confirmUninstall = true }) {
                Text(stringResource(MR.strings.ext_uninstall))
            }
        }
    }

    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = {
                Text(stringResource(MR.strings.tsuzuki_addon_uninstall_title))
            },
            text = {
                Text(
                    stringResource(
                        MR.strings.tsuzuki_addon_uninstall_message,
                        addon.displayName,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUninstall = false
                        onUninstall()
                    },
                ) {
                    Text(stringResource(MR.strings.ext_uninstall))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = false }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}
