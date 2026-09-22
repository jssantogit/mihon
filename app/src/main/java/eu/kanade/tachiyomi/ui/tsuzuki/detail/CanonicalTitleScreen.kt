package eu.kanade.tachiyomi.ui.tsuzuki.detail

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.tsuzuki.content.ContentOptionSelectorSheet
import eu.kanade.presentation.tsuzuki.detail.CanonicalTitleDetailScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.ui.tsuzuki.content.ContentSelectorScreenModel

data class CanonicalTitleScreen(
    val canonicalTitleId: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val screenModel = metroViewModel<CanonicalTitleScreenModel>()
        val contentSelectorViewModel = metroViewModel<ContentSelectorScreenModel>()
        val state by screenModel.state.collectAsStateWithLifecycle()
        val contentSelectorState by contentSelectorViewModel.state.collectAsStateWithLifecycle()

        LaunchedEffect(canonicalTitleId) {
            screenModel.start(canonicalTitleId)
        }

        CanonicalTitleDetailScreen(
            state = state,
            navigateUp = navigator::pop,
            onRefresh = { screenModel.refresh() },
            onAddToLibrary = { screenModel.addToLibrary() },
            onRemoveFromLibrary = { screenModel.removeFromLibrary() },
            onDownloadChapter = { canonicalChapterId ->
                screenModel.requestDownload(canonicalChapterId)
            },
            onOpenChapter = { canonicalChapterId ->
                context.startActivity(
                    ReaderActivity.newCanonicalIntent(
                        context = context,
                        canonicalChapterId = canonicalChapterId,
                    ),
                )
            },
        )

        val loaded = state as? CanonicalTitleScreenState.Loaded
        val downloadSelectionChapterId = loaded?.downloadSelectionChapterId
        if (downloadSelectionChapterId != null) {
            LaunchedEffect(canonicalTitleId, downloadSelectionChapterId) {
                contentSelectorViewModel.start(
                    canonicalTitleId = canonicalTitleId,
                    canonicalChapterId = downloadSelectionChapterId,
                )
            }
            ContentOptionSelectorSheet(
                state = contentSelectorState,
                onSelect = { item ->
                    val selection = contentSelectorViewModel.select(item)
                    screenModel.downloadSelectedOption(selection.option)
                },
                onRetry = { contentSelectorViewModel.retry() },
                onOpenAddonsSettings = {
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .setAction(Intent.ACTION_APPLICATION_PREFERENCES)
                            .putExtra(
                                SettingsScreen.EXTRA_DESTINATION,
                                SettingsScreen.Destination.TsuzukiAddons.id,
                            ),
                    )
                },
                onDismissRequest = screenModel::dismissDownloadSelector,
            )
        }
    }
}
