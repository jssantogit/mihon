package eu.kanade.tachiyomi.ui.tsuzuki.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.more.settings.screen.SettingsMainScreen
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Tab
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Settings

data object TsuzukiSettingsTab : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 3u,
            title = "Settings",
            icon = rememberVectorPainter(MaterialSymbols.Rounded.Settings),
        )

    override suspend fun onReselect(navigator: Navigator) = Unit

    @Composable
    override fun Content() {
        CompositionLocalProvider(LocalBackPress provides {}) {
            SettingsMainScreen.Content(
                twoPane = false,
                navigateUp = null,
            )
        }
    }
}
