package eu.kanade.tachiyomi.ui.tsuzuki.source

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.tsuzuki.source.SourceResolverScreen as SourceResolverScreenContent

class SourceResolverScreen(
    private val canonicalTitleId: String,
    private val title: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = metroViewModel<SourceResolverScreenModel>()
        val state by screenModel.state.collectAsStateWithLifecycle()

        LaunchedEffect(canonicalTitleId, title) {
            screenModel.start(canonicalTitleId, title)
        }

        SourceResolverScreenContent(
            state = state,
            navigateUp = navigator::pop,
            onSelectLanguage = screenModel::selectLanguage,
            onConfirm = screenModel::confirm,
            onCheckMoreSources = screenModel::checkMoreSources,
            onSetTitleSourceOverride = screenModel::setTitleSourceOverride,
            onRetry = screenModel::retry,
            onOpenPreferences = { navigator.push(ReadingSourcePreferencesScreen()) },
        )
    }
}
