package eu.kanade.tachiyomi.ui.tsuzuki.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.tsuzuki.catalog.CatalogScreen as CatalogScreenContent

class CatalogScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = metroViewModel<CatalogScreenModel>()
        val state by screenModel.state.collectAsStateWithLifecycle()

        CatalogScreenContent(
            state = state,
            navigateUp = navigator::pop,
            onChangeSearchQuery = screenModel::updateSearchQuery,
            onSearch = screenModel::search,
            onClickCloseSearch = screenModel::clearSearch,
            onSelectItem = screenModel::openPreview,
            onDismissPreview = screenModel::dismissPreview,
            onAddToLibrary = screenModel::addToLibrary,
            onRetry = {
                if (state.isSearching) {
                    screenModel.search()
                } else {
                    screenModel.loadDiscover()
                }
            },
        )
    }
}
