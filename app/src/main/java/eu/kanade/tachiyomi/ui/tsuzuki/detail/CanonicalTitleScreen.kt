package eu.kanade.tachiyomi.ui.tsuzuki.detail

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.flow.collect

data class CanonicalTitleScreen(
    val canonicalTitleId: String,
    val openSourceBindingFlow: Boolean = false,
    val finishOnNavigateUp: Boolean = false,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val navigateUp: () -> Unit = {
            if (finishOnNavigateUp) {
                (context as? Activity)?.finish()
            } else {
                navigator.pop()
            }
        }
        BackHandler(
            enabled = finishOnNavigateUp,
            onBack = navigateUp,
        )

        val screenModel = metroViewModel<CanonicalTitleScreenModel>()
        val contentSelectorViewModel = metroViewModel<ContentSelectorScreenModel>()
        val linkViewModel = metroViewModel<ContentBindingLinkScreenModel>()
        val linkState by linkViewModel.state.collectAsStateWithLifecycle()
        var linkSheetOpen by rememberSaveable(canonicalTitleId) { mutableStateOf(false) }
        var initialLinkFlowOpened by rememberSaveable(canonicalTitleId) { mutableStateOf(false) }
        val state by screenModel.state.collectAsStateWithLifecycle()
        val contentSelectorState by contentSelectorViewModel.state.collectAsStateWithLifecycle()
        val loaded = (state as? CanonicalTitleScreenState.Loaded)
            ?.takeIf { it.title.id == canonicalTitleId }
        val downloadSelectionChapterId = loaded?.downloadSelectionChapterId

        LaunchedEffect(canonicalTitleId, openSourceBindingFlow) {
            screenModel.start(canonicalTitleId)
            if (
                shouldAutoOpenSourceBindingFlow(
                    canonicalTitleId,
                    openSourceBindingFlow,
                    initialLinkFlowOpened,
                )
            ) {
                initialLinkFlowOpened = true
                linkViewModel.start(canonicalTitleId)
                linkSheetOpen = true
            }
        }

        CanonicalTitleDetailScreen(
            state = state,
            navigateUp = navigateUp,
            onAtualizar = { screenModel.refresh() },
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
            onBaixarChapter = { canonicalChapterId ->
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

        LaunchedEffect(linkViewModel, canonicalTitleId) {
            linkViewModel.bindingUpdates.collect { request ->
                if (request.canonicalTitleId != canonicalTitleId) return@collect
                // A manually linked edition should not re-fetch every existing
                // source inventory before its chapter can be selected.
                if (contentSelectorViewModel.refreshAfterBindings(request).isSuccess) {
                    screenModel.reloadReconciledChapters()?.join()
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
                    contentSelectorViewModel.cancelDiscovery()
                    linkViewModel.start(sourceCanonicalTitleId)
                    linkSheetOpen = true
                },
                onCancelDiscovery = contentSelectorViewModel::cancelDiscovery,
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
                onDismissRequest = {
                    contentSelectorViewModel.cancelDiscovery()
                    screenModel.dismissDownloadSelector()
                },
            )
        }
    }
}

internal fun shouldAutoOpenSourceBindingFlow(
    canonicalTitleId: String,
    requested: Boolean,
    alreadyOpened: Boolean,
): Boolean = canonicalTitleId.isNotBlank() && requested && !alreadyOpened
