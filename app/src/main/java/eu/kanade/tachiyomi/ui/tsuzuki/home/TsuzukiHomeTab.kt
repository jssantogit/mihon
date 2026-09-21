package eu.kanade.tachiyomi.ui.tsuzuki.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.tsuzuki.home.TsuzukiHomeScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity

data object TsuzukiHomeTab : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 0u,
            title = "Home",
            icon = painterResource(R.drawable.ic_tsuzuki_home_24dp),
        )

    override suspend fun onReselect(navigator: Navigator) = Unit

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val screenModel = metroViewModel<TsuzukiHomeScreenModel>()
        val state by screenModel.state.collectAsStateWithLifecycle()

        TsuzukiHomeScreen(
            state = state,
            onContinueReading = { item ->
                context.startActivity(
                    ReaderActivity.newCanonicalIntent(
                        context = context,
                        canonicalChapterId = item.canonicalChapterId,
                    ),
                )
            },
            onRemoveFromContinueReading = screenModel::removeFromContinueReading,
        )

        LaunchedEffect(Unit) {
            (context as? MainActivity)?.ready = true
        }
    }
}
