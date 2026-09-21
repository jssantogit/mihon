package eu.kanade.tachiyomi.ui.tsuzuki.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.tsuzuki.detail.CanonicalTitleDetailScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity

data class CanonicalTitleScreen(
    val canonicalTitleId: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val screenModel = metroViewModel<CanonicalTitleScreenModel>()
        val state by screenModel.state.collectAsStateWithLifecycle()

        LaunchedEffect(canonicalTitleId) {
            screenModel.start(canonicalTitleId)
        }

        CanonicalTitleDetailScreen(
            state = state,
            navigateUp = navigator::pop,
            onRefresh = { screenModel.refresh() },
            onOpenChapter = { canonicalChapterId ->
                context.startActivity(
                    ReaderActivity.newCanonicalIntent(
                        context = context,
                        canonicalChapterId = canonicalChapterId,
                    ),
                )
            },
        )
    }
}
