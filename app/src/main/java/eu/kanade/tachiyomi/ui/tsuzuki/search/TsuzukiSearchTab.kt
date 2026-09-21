package eu.kanade.tachiyomi.ui.tsuzuki.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.more.settings.screen.SettingsTsuzukiIntegrationsScreen
import eu.kanade.presentation.tsuzuki.search.TsuzukiSearchScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.tsuzuki.detail.CanonicalTitleScreen

data object TsuzukiSearchTab : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 2u,
            title = "Search",
            icon = painterResource(R.drawable.ic_book_24dp),
        )

    override suspend fun onReselect(navigator: Navigator) = Unit

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = metroViewModel<TsuzukiSearchScreenModel>()
        val state by screenModel.state.collectAsStateWithLifecycle()
        var query by rememberSaveable { mutableStateOf("") }

        LaunchedEffect(screenModel) {
            screenModel.events.collect { event ->
                when (event) {
                    is TsuzukiSearchEvent.OpenCanonicalTitle -> {
                        navigator.push(
                            CanonicalTitleScreen(event.canonicalTitleId),
                        )
                    }
                }
            }
        }

        TsuzukiSearchScreen(
            state = state,
            query = query,
            onQueryChange = { query = it },
            onSearch = screenModel::search,
            onRecentSearch = {
                query = it
                screenModel.search(it)
            },
            onResultClick = screenModel::openResult,
            onClearRecent = screenModel::clearRecentSearches,
            onRetry = screenModel::loadDiscover,
            onOpenIntegrations = {
                navigator.push(SettingsTsuzukiIntegrationsScreen())
            },
        )
    }
}
