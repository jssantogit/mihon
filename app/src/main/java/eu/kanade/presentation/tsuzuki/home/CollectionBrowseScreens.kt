package eu.kanade.presentation.tsuzuki.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.ui.tsuzuki.home.CollectionBrowseScreenState
import eu.kanade.tachiyomi.ui.tsuzuki.home.FolderCatalogContent
import eu.kanade.tachiyomi.ui.tsuzuki.home.FolderCatalogScreenState
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.presentation.core.components.material.Scaffold

@Composable
fun CollectionBrowseScreen(
    state: CollectionBrowseScreenState,
    navigateUp: () -> Unit,
    onFolder: (folderId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = (state as? CollectionBrowseScreenState.Ready)
        ?.collection
        ?.title
        ?: "Coleção"

    Scaffold(
        modifier = modifier,
        topBar = {
            AppBar(
                titleContent = { AppBarTitle(title) },
                navigateUp = navigateUp,
            )
        },
    ) { paddingValues ->
        when (state) {
            CollectionBrowseScreenState.Loading -> BrowseLoading(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            )
            CollectionBrowseScreenState.Missing -> BrowseMessage(
                message = "Coleção indisponível",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            )
            is CollectionBrowseScreenState.Error -> BrowseMessage(
                message = state.message,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            )
            is CollectionBrowseScreenState.Ready -> {
                LazyRow(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(
                        horizontal = 16.dp,
                        vertical = 16.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        items = state.collection.folders,
                        key = { folder -> folder.folderId },
                    ) { folder ->
                        FolderTile(
                            folder = folder,
                            onClick = { onFolder(folder.folderId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FolderCatalogScreen(
    state: FolderCatalogScreenState,
    navigateUp: () -> Unit,
    onChildFolder: (folderId: String) -> Unit,
    onSelectList: (listId: String) -> Unit,
    onCatalogItem: (CatalogItem) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = (state as? FolderCatalogScreenState.Ready)
        ?.folder
        ?.title
        ?: "Pasta"

    Scaffold(
        modifier = modifier,
        topBar = {
            AppBar(
                titleContent = { AppBarTitle(title) },
                navigateUp = navigateUp,
            )
        },
    ) { paddingValues ->
        when (state) {
            FolderCatalogScreenState.Loading -> BrowseLoading(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            )
            FolderCatalogScreenState.Missing -> BrowseMessage(
                message = "Pasta indisponível",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            )
            is FolderCatalogScreenState.Error -> BrowseMessage(
                message = state.message,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            )
            is FolderCatalogScreenState.Ready -> FolderCatalogReady(
                state = state,
                onChildFolder = onChildFolder,
                onSelectList = onSelectList,
                onCatalogItem = onCatalogItem,
                onLoadMore = onLoadMore,
                onRetry = onRetry,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            )
        }
    }
}

@Composable
private fun FolderCatalogReady(
    state: FolderCatalogScreenState.Ready,
    onChildFolder: (folderId: String) -> Unit,
    onSelectList: (listId: String) -> Unit,
    onCatalogItem: (CatalogItem) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabledLists = state.folder.lists.filter { it.enabled }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            horizontal = 16.dp,
            vertical = 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (state.folder.childFolders.isNotEmpty()) {
            item(key = "child_folders") {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        items = state.folder.childFolders,
                        key = { child -> child.folderId },
                    ) { child ->
                        FolderTile(
                            folder = child,
                            onClick = { onChildFolder(child.folderId) },
                        )
                    }
                }
            }
        }

        if (enabledLists.isNotEmpty()) {
            item(key = "list_selector") {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = enabledLists,
                        key = { list -> list.id },
                    ) { list ->
                        FilterChip(
                            selected = state.selectedListId == list.id,
                            onClick = { onSelectList(list.id) },
                            label = {
                                Text(
                                    text = list.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
        }

        when (val content = state.content) {
            FolderCatalogContent.Idle -> {
                if (enabledLists.isEmpty()) {
                    item(key = "empty_lists") {
                        Text(
                            text = if (state.folder.childFolders.isEmpty()) {
                                "Nenhuma lista ativa nesta pasta"
                            } else {
                                "Nenhuma lista ativa nesta pasta ainda"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            FolderCatalogContent.Loading -> {
                item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
            is FolderCatalogContent.Error -> {
                item(key = "error") {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = content.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = onRetry) {
                            Text("Tentar novamente")
                        }
                    }
                }
            }
            is FolderCatalogContent.Content -> {
                if (content.items.isEmpty()) {
                    item(key = "empty_catalog") {
                        Text(
                            text = "Nenhum título encontrado",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    content.items
                        .chunked(CATALOG_COLUMNS)
                        .forEachIndexed { rowIndex, rowItems ->
                            item(key = "catalog_row_$rowIndex") {
                                CatalogGridRow(
                                    items = rowItems,
                                    onCatalogItem = onCatalogItem,
                                )
                            }
                        }
                }

                if (content.nextCursor != null) {
                    item(key = "load_more") {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            TextButton(onClick = onLoadMore) {
                                Text("Carregar mais")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogGridRow(
    items: List<CatalogItem>,
    onCatalogItem: (CatalogItem) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        items.forEach { item ->
            CatalogGridItem(
                item = item,
                onClick = { onCatalogItem(item) },
                modifier = Modifier.weight(1f),
            )
        }
        repeat(CATALOG_COLUMNS - items.size) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun CatalogGridItem(
    item: CatalogItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MangaCover.Book(
            data = item.coverUrl,
            contentDescription = item.title,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BrowseLoading(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun BrowseMessage(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val CATALOG_COLUMNS = 3
