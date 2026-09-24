package eu.kanade.tachiyomi.ui.tsuzuki.detail

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.tsuzuki.content.ContentBindingLinkSheet
import eu.kanade.presentation.tsuzuki.content.ContentOptionSelectorSheet
import eu.kanade.presentation.tsuzuki.detail.CanonicalTitleDetailScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.ui.tsuzuki.content.ContentBindingLinkScreenModel
import eu.kanade.tachiyomi.ui.tsuzuki.content.ContentBindingLinkState
import eu.kanade.tachiyomi.ui.tsuzuki.content.ContentSelectorScreenModel
import eu.kanade.tachiyomi.util.system.copyToClipboard
import kotlinx.coroutines.flow.collect

data class CanonicalTitleScreen(
    val canonicalTitleId: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val screenModel = metroViewModel<CanonicalTitleScreenModel>()
        val contentSelectorViewModel = metroViewModel<ContentSelectorScreenModel>()
        val linkViewModel = metroViewModel<ContentBindingLinkScreenModel>()
        val linkState by linkViewModel.state.collectAsStateWithLifecycle()
        var linkSheetOpen by remember { mutableStateOf(false) }
        val state by screenModel.state.collectAsStateWithLifecycle()
        val contentSelectorState by contentSelectorViewModel.state.collectAsStateWithLifecycle()
        val loaded = (state as? CanonicalTitleScreenState.Loaded)
            ?.takeIf { it.title.id == canonicalTitleId }
        val downloadSelectionChapterId = loaded?.downloadSelectionChapterId
        val currentDownloadSelectionChapterId = rememberUpdatedState(downloadSelectionChapterId)

        LaunchedEffect(canonicalTitleId) {
            screenModel.start(canonicalTitleId)
        }

        CanonicalTitleDetailScreen(
            state = state,
            navigateUp = navigator::pop,
            onRefresh = { screenModel.refresh() },
            onLinkReadingAddon = {
                linkViewModel.start(canonicalTitleId)
                linkSheetOpen = true
            },
            onStartChapterDiagnostics = screenModel::startChapterDiagnostics,
            onStopChapterDiagnostics = screenModel::stopChapterDiagnostics,
            onCopyChapterDiagnostics = {
                screenModel.chapterDiagnosticReport()
                    .takeIf(String::isNotBlank)
                    ?.let { context.copyToClipboard("Tsuzuki chapter inventory diagnostic", it) }
            },
            onClearChapterDiagnostics = screenModel::clearChapterDiagnostics,
            onAddToLibrary = { screenModel.addToLibrary() },
            onRemoveFromLibrary = { screenModel.removeFromLibrary() },
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
            onDownloadChapter = { canonicalChapterId ->
                screenModel.requestDownload(canonicalChapterId)
            },
            onOpenChapter = { canonicalChapterId ->
                screenModel.openChapter(canonicalChapterId) { actualChapterId ->
                    context.startActivity(
                        ReaderActivity.newCanonicalIntent(
                            context = context,
                            canonicalChapterId = actualChapterId,
                        ),
                    )
                }
            },
        )

        LaunchedEffect(linkViewModel) {
            linkViewModel.bindingChanges.collect {
                screenModel.refresh()
                if (currentDownloadSelectionChapterId.value != null) {
                    contentSelectorViewModel.retry()
                }
            }
        }
        if (linkSheetOpen) {
            ContentBindingLinkSheet(
                state = linkState,
                onSelectAddon = linkViewModel::selectAddon,
                onConfirmCandidate = linkViewModel::confirm,
                onSearchMore = linkViewModel::searchMore,
                onBack = linkViewModel::backToAddons,
                onDismiss = {
                    linkSheetOpen = false
                    linkViewModel.close()
                },
            )
        }

        if (downloadSelectionChapterId != null && !linkSheetOpen) {
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
                onFindOrAddSource = { sourceCanonicalTitleId ->
                    linkViewModel.start(sourceCanonicalTitleId)
                    linkSheetOpen = true
                },
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
