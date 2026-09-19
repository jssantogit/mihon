package eu.kanade.tachiyomi.ui.tsuzuki.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.tsuzuki.source.ReadingSourcePreferencesScreen
import eu.kanade.tachiyomi.ui.tsuzuki.source.SourceResolverScreen
import eu.kanade.presentation.tsuzuki.library.CanonicalLibraryScreen as CanonicalLibraryScreenContent

class CanonicalLibraryScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = metroViewModel<CanonicalLibraryScreenModel>()
        val state by screenModel.state.collectAsStateWithLifecycle()

        CanonicalLibraryScreenContent(
            state = state,
            navigateUp = navigator::pop,
            onUpdateStatus = screenModel::setStatus,
            onRemoveItem = screenModel::removeItem,
            onResolveSource = { item ->
                navigator.push(SourceResolverScreen(item.title.id, item.title.displayTitle))
            },
            onOpenSourcePreferences = { navigator.push(ReadingSourcePreferencesScreen()) },
        )
    }
}
