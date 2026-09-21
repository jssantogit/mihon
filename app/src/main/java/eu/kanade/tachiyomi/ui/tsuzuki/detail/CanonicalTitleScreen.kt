package eu.kanade.tachiyomi.ui.tsuzuki.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.tsuzuki.detail.CanonicalTitleDetailScreen
import eu.kanade.presentation.util.Screen

class CanonicalTitleScreen(
    private val canonicalTitleId: String,
    private val onOpenChapter: (String) -> Unit = {},
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = metroViewModel<CanonicalTitleScreenModel>()
        val state by screenModel.state.collectAsStateWithLifecycle()

        LaunchedEffect(canonicalTitleId) {
            screenModel.start(canonicalTitleId)
        }

        CanonicalTitleDetailScreen(
            state = state,
            navigateUp = navigator::pop,
            onRefresh = { screenModel.refresh() },
            onOpenChapter = onOpenChapter,
        )
    }
}
