package eu.kanade.tachiyomi.ui.tsuzuki.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.tsuzuki.detail.CanonicalTitleScreen
import eu.kanade.presentation.tsuzuki.home.CollectionBrowseScreen as CollectionBrowseScreenContent
import eu.kanade.presentation.tsuzuki.home.FolderCatalogScreen as FolderCatalogScreenContent

class HomeCollectionScreen(
    private val collectionId: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = metroViewModel<CollectionBrowseScreenModel>()
        val state by screenModel.state.collectAsStateWithLifecycle()

        LaunchedEffect(collectionId) {
            screenModel.bind(collectionId)
        }

        CollectionBrowseScreenContent(
            state = state,
            navigateUp = navigator::pop,
            onFolder = { folderId ->
                navigator.push(
                    HomeFolderScreen(
                        collectionId = collectionId,
                        folderId = folderId,
                    ),
                )
            },
        )
    }
}

class HomeFolderScreen(
    private val collectionId: String,
    private val folderId: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = metroViewModel<FolderCatalogScreenModel>()
        val state by screenModel.state.collectAsStateWithLifecycle()

        LaunchedEffect(collectionId, folderId) {
            screenModel.bind(
                collectionId = collectionId,
                folderId = folderId,
            )
        }

        FolderCatalogScreenContent(
            state = state,
            navigateUp = navigator::pop,
            onChildFolder = { childFolderId ->
                navigator.push(
                    HomeFolderScreen(
                        collectionId = collectionId,
                        folderId = childFolderId,
                    ),
                )
            },
            onSelectList = screenModel::selectList,
            onCatalogItem = screenModel::openCatalogItem,
            onLoadMore = screenModel::loadMore,
            onRetry = screenModel::retry,
        )

        LaunchedEffect(screenModel) {
            screenModel.events.collect { event ->
                when (event) {
                    is FolderCatalogEvent.OpenCanonicalTitle -> {
                        navigator.push(
                            CanonicalTitleScreen(event.canonicalTitleId),
                        )
                    }
                }
            }
        }
    }
}
