package eu.kanade.presentation.tsuzuki.collections

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.tsuzuki.catalog.CatalogCompactCard
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionFolderUiModel
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionListDraft
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionListRuntimeState
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionUiModel
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionsAction
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionsScreenState
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionsTransferState
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.DragHandle
import mihon.icons.materialsymbols.rounded.Edit
import mihon.icons.materialsymbols.rounded.MoreVert
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.collections.model.CollectionFolder
import tachiyomi.domain.tsuzuki.collections.model.CollectionList
import tachiyomi.domain.tsuzuki.collections.model.CollectionOrigin
import tachiyomi.domain.tsuzuki.collections.model.TsuzukiCollection
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

@Composable
fun CollectionsScreen(
    state: CollectionsScreenState,
    navigateUp: () -> Unit,
    onAction: (CollectionsAction) -> Unit,
    onImportRequest: () -> Unit,
    onExportRequest: () -> Unit,
    onDismissTransferState: () -> Unit,
    modifier: Modifier = Modifier,
    initialCollectionId: String? = null,
    initialFolderId: String? = null,
) {
    var editor by remember { mutableStateOf<EditorDialog?>(null) }
    var deleteTarget by remember { mutableStateOf<DeleteTarget?>(null) }
    var selectedCollectionId by remember(initialCollectionId) { mutableStateOf(initialCollectionId) }
    var selectedFolderId by remember(initialFolderId) { mutableStateOf(initialFolderId) }
    var selectedListId by remember { mutableStateOf<String?>(null) }

    val readyState = state as? CollectionsScreenState.Ready
    val selectedCollection = readyState?.collections?.firstOrNull {
        it.collection.id == selectedCollectionId
    }
    val selectedFolder = selectedCollection?.folders?.firstOrNull {
        it.folder.id == selectedFolderId
    }
    val selectedList = selectedFolder?.lists?.firstOrNull { it.id == selectedListId }

    val navigateWithinCollections: () -> Unit = {
        when {
            selectedListId != null -> selectedListId = null
            selectedFolderId != null -> selectedFolderId = null
            selectedCollectionId != null -> selectedCollectionId = null
            else -> navigateUp()
        }
    }

    val currentEditor = editor
    val editedCollectionGraph = (currentEditor as? EditorDialog.RenameCollection)
        ?.let { target ->
            readyState?.collections?.firstOrNull { it.collection.id == target.collection.id }
        }
    val editedFolderModel = (currentEditor as? EditorDialog.RenameFolder)
        ?.let { target ->
            readyState?.collections
                ?.asSequence()
                ?.flatMap { it.folders.asSequence() }
                ?.firstOrNull { it.folder.id == target.folder.id }
        }

    val fullScreenEditorRendered = when (currentEditor) {
        EditorDialog.CreateCollection -> {
            CollectionEditorScreen(
                graph = null,
                onAction = onAction,
                onDelete = { deleteTarget = it },
                onClose = { editor = null },
            )
            true
        }

        is EditorDialog.RenameCollection -> {
            editedCollectionGraph?.let { graph ->
                CollectionEditorScreen(
                    graph = graph,
                    onAction = onAction,
                    onDelete = { deleteTarget = it },
                    onClose = { editor = null },
                )
            } != null
        }

        is EditorDialog.CreateFolder -> {
            FolderEditorScreen(
                collectionId = currentEditor.collectionId,
                parentFolderId = currentEditor.parentFolderId,
                existing = null,
                initialDraft = null,
                onSubmitDraft = null,
                onAction = onAction,
                onDelete = { deleteTarget = it },
                onClose = { editor = null },
            )
            true
        }

        is EditorDialog.RenameFolder -> {
            editedFolderModel?.let { model ->
                FolderEditorScreen(
                    collectionId = model.folder.collectionId,
                    parentFolderId = model.folder.parentFolderId,
                    existing = model,
                    initialDraft = null,
                    onSubmitDraft = null,
                    onAction = onAction,
                    onDelete = { deleteTarget = it },
                    onClose = { editor = null },
                )
            } != null
        }

        is EditorDialog.CreateList -> {
            ListBuilderScreen(
                title = "New List",
                initial = ListEditorState.empty(),
                onClose = { editor = null },
                onConfirm = { draft ->
                    onAction(
                        CollectionsAction.CreateList(
                            collectionId = currentEditor.collectionId,
                            folderId = currentEditor.folderId,
                            draft = draft,
                        ),
                    )
                    editor = null
                },
            )
            true
        }

        is EditorDialog.EditList -> {
            ListBuilderScreen(
                title = "Edit List",
                initial = ListEditorState.from(currentEditor.list),
                onClose = { editor = null },
                onConfirm = { draft ->
                    onAction(CollectionsAction.UpdateList(currentEditor.list, draft))
                    editor = null
                },
            )
            true
        }

        else -> false
    }

    if (!fullScreenEditorRendered) {
        BackHandler(
            enabled = selectedListId != null || selectedFolderId != null || selectedCollectionId != null,
            onBack = navigateWithinCollections,
        )

        Scaffold(
            modifier = modifier,
            topBar = {
                AppBar(
                    title = selectedList?.title
                        ?: selectedFolder?.folder?.title
                        ?: selectedCollection?.collection?.title
                        ?: "Collections",
                    navigateUp = navigateWithinCollections,
                    actions = {
                        AppBarActions(
                            listOf(
                                AppBar.OverflowAction(
                                    title = "Import",
                                    onClick = onImportRequest,
                                ),
                                AppBar.OverflowAction(
                                    title = "Export",
                                    onClick = onExportRequest,
                                ),
                            ),
                        )
                    },
                )
            },
        ) { padding ->
            when (state) {
                CollectionsScreenState.Loading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is CollectionsScreenState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                is CollectionsScreenState.Ready -> {
                    CollectionsReadyContent(
                        collections = state.collections,
                        transferState = state.transferState,
                        listRuntimeStates = state.listRuntimeStates,
                        onAction = onAction,
                        onEdit = { editor = it },
                        onDelete = { deleteTarget = it },
                        selectedCollectionId = selectedCollectionId,
                        selectedFolderId = selectedFolderId,
                        selectedListId = selectedListId,
                        onOpenCollection = {
                            selectedCollectionId = it
                            selectedFolderId = null
                            selectedListId = null
                        },
                        onOpenFolder = {
                            selectedFolderId = it
                            selectedListId = null
                        },
                        onOpenList = { selectedListId = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    )

                    when (val transfer = state.transferState) {
                        CollectionsTransferState.Idle,
                        is CollectionsTransferState.ExportReady,
                        -> Unit

                        CollectionsTransferState.Working -> {
                            AlertDialog(
                                onDismissRequest = {},
                                confirmButton = {},
                                title = { Text("Collections") },
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        CircularProgressIndicator()
                                        Text("Working…")
                                    }
                                },
                            )
                        }

                        is CollectionsTransferState.ImportSucceeded -> {
                            AlertDialog(
                                onDismissRequest = onDismissTransferState,
                                confirmButton = {
                                    TextButton(onClick = onDismissTransferState) {
                                        Text("OK")
                                    }
                                },
                                title = { Text("Import complete") },
                                text = {
                                    Text(
                                        "Imported ${transfer.result.collectionIds.size} collection(s), " +
                                            "${transfer.result.folderCount} folder(s), and " +
                                            "${transfer.result.listCount} list(s). " +
                                            "${transfer.result.remappedIdCount} id(s) were remapped.",
                                    )
                                },
                            )
                        }

                        is CollectionsTransferState.Error -> {
                            AlertDialog(
                                onDismissRequest = onDismissTransferState,
                                confirmButton = {
                                    TextButton(onClick = onDismissTransferState) {
                                        Text("OK")
                                    }
                                },
                                title = { Text("Collections error") },
                                text = { Text(transfer.message) },
                            )
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAction(target.action)
                        deleteTarget = null
                    },
                ) {
                    Text(if (target.systemOwned) "Hide" else "Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel")
                }
            },
            title = {
                Text(if (target.systemOwned) "Hide built-in item?" else "Delete item?")
            },
            text = {
                Text(
                    if (target.systemOwned) {
                        "This built-in definition will stay hidden on this device."
                    } else {
                        "This uses a tombstone so future sync can preserve the deletion."
                    },
                )
            },
        )
    }
}

@Composable
private fun CollectionsReadyContent(
    collections: List<CollectionUiModel>,
    transferState: CollectionsTransferState,
    listRuntimeStates: Map<String, CollectionListRuntimeState>,
    onAction: (CollectionsAction) -> Unit,
    onEdit: (EditorDialog) -> Unit,
    onDelete: (DeleteTarget) -> Unit,
    selectedCollectionId: String?,
    selectedFolderId: String?,
    selectedListId: String?,
    onOpenCollection: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenList: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedCollection = collections.firstOrNull { it.collection.id == selectedCollectionId }
    val selectedFolder = selectedCollection?.folders?.firstOrNull { it.folder.id == selectedFolderId }
    val selectedList = selectedFolder?.lists?.firstOrNull { it.id == selectedListId }
    var displayedCollections by remember(collections) { mutableStateOf(collections) }
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromIndex = from.index - COLLECTION_LIST_HEADER_COUNT
        val toIndex = to.index - COLLECTION_LIST_HEADER_COUNT
        if (fromIndex in displayedCollections.indices && toIndex in displayedCollections.indices) {
            displayedCollections = displayedCollections.toMutableList().apply {
                add(toIndex, removeAt(fromIndex))
            }
        }
    }

    LazyColumn(
        modifier = modifier,
        state = lazyListState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when {
            selectedCollection == null -> {
                item(key = "collections_overview") {
                    CollectionsOverviewCard(
                        collections = collections,
                        onCreate = { onEdit(EditorDialog.CreateCollection) },
                    )
                }

                if (collections.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = "No Collections yet. Create one to organize catalog queries.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                items(
                    items = displayedCollections,
                    key = { graph -> "collection:${graph.collection.id}" },
                ) { graph ->
                    val itemKey = "collection:${graph.collection.id}"
                    val reorderEnabled = displayedCollections.size > 1
                    ReorderableItem(
                        state = reorderableState,
                        key = itemKey,
                        enabled = reorderEnabled,
                    ) { isDragging ->
                        CollectionCard(
                            graph = graph,
                            isDragging = isDragging,
                            reorderEnabled = reorderEnabled,
                            reorderHandleModifier = if (reorderEnabled) {
                                Modifier.draggableHandle(
                                    onDragStopped = {
                                        val persistedOrder = collections.map { it.collection.id }
                                        val displayedOrder = displayedCollections.map { it.collection.id }
                                        if (displayedOrder != persistedOrder) {
                                            onAction(
                                                CollectionsAction.ReorderCollections(
                                                    orderedCollectionIds = displayedOrder,
                                                ),
                                            )
                                        }
                                    },
                                )
                            } else {
                                Modifier
                            },
                            onEdit = onEdit,
                            onDelete = onDelete,
                            onDuplicate = {
                                onAction(CollectionsAction.DuplicateCollection(graph.collection.id))
                            },
                            onOpen = {
                                if (graph.collection.origin == CollectionOrigin.USER) {
                                    onEdit(EditorDialog.RenameCollection(graph.collection))
                                } else {
                                    onOpenCollection(graph.collection.id)
                                }
                            },
                        )
                    }
                }
            }

            selectedFolder == null -> {
                val visibleFolders = selectedCollection.folders.filter { it.depth == 0 }
                if (visibleFolders.isEmpty()) {
                    item(key = "empty_folders") {
                        Text("No folders in this Collection yet.")
                    }
                }
                visibleFolders.forEach { folder ->
                    item(key = "folder:${folder.folder.id}") {
                        FolderRow(
                            collectionId = selectedCollection.collection.id,
                            model = folder,
                            onAction = onAction,
                            onEdit = onEdit,
                            onDelete = onDelete,
                            onOpen = { onOpenFolder(folder.folder.id) },
                        )
                    }
                }
            }

            else -> {
                val childFolders = selectedCollection.folders.filter {
                    it.folder.parentFolderId == selectedFolder.folder.id
                }
                if (selectedList == null) {
                    childFolders.forEach { folder ->
                        item(key = "subfolder:${folder.folder.id}") {
                            FolderRow(
                                collectionId = selectedCollection.collection.id,
                                model = folder,
                                onAction = onAction,
                                onEdit = onEdit,
                                onDelete = onDelete,
                                onOpen = { onOpenFolder(folder.folder.id) },
                            )
                        }
                    }
                    selectedFolder.lists.forEach { list ->
                        item(key = "list:${list.id}") {
                            ListSummaryRow(
                                list = list,
                                onOpen = { onOpenList(list.id) },
                            )
                        }
                    }
                    if (childFolders.isEmpty() && selectedFolder.lists.isEmpty()) {
                        item(key = "empty_folder") {
                            Text("This folder has no subfolders or Lists yet.")
                        }
                    }
                } else {
                    item(key = "list_detail:${selectedList.id}") {
                        ListRow(
                            folderDepth = 0,
                            list = selectedList,
                            runtimeState = listRuntimeStates[selectedList.id]
                                ?: CollectionListRuntimeState.Idle,
                            onAction = onAction,
                            onEdit = onEdit,
                            onDelete = onDelete,
                        )
                    }
                }
            }
        }

        if (transferState is CollectionsTransferState.ExportReady) {
            item(key = "export_ready") {
                Text(
                    text = "Choose a destination for the JSON export.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CollectionsOverviewCard(
    collections: List<CollectionUiModel>,
    onCreate: () -> Unit,
) {
    val folderCount = collections.sumOf { it.folders.size }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "YOUR COLLECTIONS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${countLabel(collections.size, "collection")} · ${countLabel(folderCount, "folder")}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onCreate,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Add,
                    contentDescription = null,
                )
                Text(
                    text = "New collection",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun CollectionCard(
    graph: CollectionUiModel,
    isDragging: Boolean,
    reorderEnabled: Boolean,
    reorderHandleModifier: Modifier,
    onEdit: (EditorDialog) -> Unit,
    onDelete: (DeleteTarget) -> Unit,
    onDuplicate: () -> Unit,
    onOpen: () -> Unit,
) {
    val collection = graph.collection
    val previewFolders = graph.folders.take(COLLECTION_PREVIEW_FOLDER_COUNT)
    val hiddenFolderCount = graph.folders.size - previewFolders.size
    var menuExpanded by remember(collection.id) { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isDragging) 6.dp else 0.dp,
        ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = collection.title,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = countLabel(graph.folders.size, "folder"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.MoreVert,
                            contentDescription = "Collection actions",
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        if (collection.origin == CollectionOrigin.USER) {
                            DropdownMenuItem(
                                text = { Text("New folder") },
                                onClick = {
                                    menuExpanded = false
                                    onEdit(
                                        EditorDialog.CreateFolder(
                                            collectionId = collection.id,
                                            parentFolderId = null,
                                        ),
                                    )
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Duplicate") },
                            onClick = {
                                menuExpanded = false
                                onDuplicate()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(if (collection.origin == CollectionOrigin.SYSTEM) "Hide" else "Delete")
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete(
                                    DeleteTarget(
                                        action = CollectionsAction.DeleteCollection(collection.id),
                                        systemOwned = collection.origin == CollectionOrigin.SYSTEM,
                                    ),
                                )
                            },
                        )
                    }
                }
            }

            if (previewFolders.isEmpty()) {
                Text(
                    text = "No folders yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = previewFolders.joinToString(separator = " · ") { it.folder.title },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (hiddenFolderCount > 0) {
                        Text(
                            text = "+$hiddenFolderCount more",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CollectionReorderHandle(
                    modifier = reorderHandleModifier,
                    enabled = reorderEnabled,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (collection.origin == CollectionOrigin.USER) {
                    IconButton(
                        onClick = { onEdit(EditorDialog.RenameCollection(collection)) },
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.Edit,
                            contentDescription = "Rename collection",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionReorderHandle(
    modifier: Modifier,
    enabled: Boolean,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .then(modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MaterialSymbols.Rounded.DragHandle,
            contentDescription = "Drag to reorder collection",
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
        )
    }
}

private fun countLabel(
    count: Int,
    singular: String,
): String = if (count == 1) {
    "1 $singular"
} else {
    "$count ${singular}s"
}

@Composable
private fun FolderRow(
    collectionId: String,
    model: CollectionFolderUiModel,
    onAction: (CollectionsAction) -> Unit,
    onEdit: (EditorDialog) -> Unit,
    onDelete: (DeleteTarget) -> Unit,
    onOpen: () -> Unit,
) {
    val folder = model.folder

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (model.depth * 20).dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = folder.title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (folder.origin == CollectionOrigin.USER) {
                TextButton(
                    onClick = {
                        onEdit(
                            EditorDialog.CreateFolder(
                                collectionId = collectionId,
                                parentFolderId = folder.id,
                            ),
                        )
                    },
                ) {
                    Text("+ Subfolder")
                }
                TextButton(
                    onClick = {
                        onEdit(
                            EditorDialog.CreateList(
                                collectionId = collectionId,
                                folderId = folder.id,
                            ),
                        )
                    },
                ) {
                    Text("+ List")
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (folder.origin == CollectionOrigin.USER) {
                TextButton(onClick = { onEdit(EditorDialog.RenameFolder(folder)) }) {
                    Text("Rename")
                }
            }
            TextButton(onClick = { onAction(CollectionsAction.MoveFolder(folder, -1)) }) {
                Text("↑")
            }
            TextButton(onClick = { onAction(CollectionsAction.MoveFolder(folder, 1)) }) {
                Text("↓")
            }
            TextButton(
                onClick = {
                    onDelete(
                        DeleteTarget(
                            action = CollectionsAction.DeleteFolder(folder.id),
                            systemOwned = folder.origin == CollectionOrigin.SYSTEM,
                        ),
                    )
                },
            ) {
                Text(if (folder.origin == CollectionOrigin.SYSTEM) "Hide" else "Delete")
            }
        }

        HorizontalDivider()
    }
}

@Composable
private fun ListSummaryRow(
    list: CollectionList,
    onOpen: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = list.title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = list.providerId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ListRow(
    folderDepth: Int,
    list: CollectionList,
    runtimeState: CollectionListRuntimeState,
    onAction: (CollectionsAction) -> Unit,
    onEdit: (EditorDialog) -> Unit,
    onDelete: (DeleteTarget) -> Unit,
) {
    DisposableEffect(list.id, list.enabled) {
        if (list.enabled) {
            onAction(CollectionsAction.ListVisibilityChanged(list.id, visible = true))
        }
        onDispose {
            onAction(CollectionsAction.ListVisibilityChanged(list.id, visible = false))
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ((folderDepth + 1) * 20).dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = list.title,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "${list.providerId} • ${list.sort.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = list.enabled,
                    onCheckedChange = {
                        onAction(CollectionsAction.SetListEnabled(list.id, it))
                    },
                )
            }

            Text(
                text = list.query?.toCanonicalString() ?: "No filters",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (runtimeState) {
                CollectionListRuntimeState.Idle -> {
                    if (list.enabled) {
                        Text(
                            text = "Dormant until visible",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                CollectionListRuntimeState.Loading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Text(
                            text = "Loading results…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                is CollectionListRuntimeState.Content -> {
                    if (runtimeState.items.isEmpty()) {
                        Text(
                            text = "No matching titles",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(
                                items = runtimeState.items,
                                key = { "${it.provider}:${it.providerId}" },
                            ) { item ->
                                CatalogCompactCard(
                                    item = item,
                                    onClick = {},
                                )
                            }
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(
                            onClick = { onAction(CollectionsAction.RefreshList(list.id)) },
                        ) {
                            Text("Refresh")
                        }
                        if (runtimeState.nextCursor != null) {
                            TextButton(
                                onClick = { onAction(CollectionsAction.LoadMore(list.id)) },
                            ) {
                                Text("Load more")
                            }
                        }
                    }
                }

                is CollectionListRuntimeState.Error -> {
                    Text(
                        text = runtimeState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(
                        onClick = { onAction(CollectionsAction.RefreshList(list.id)) },
                    ) {
                        Text("Retry")
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (list.origin == CollectionOrigin.USER) {
                    TextButton(onClick = { onEdit(EditorDialog.EditList(list)) }) {
                        Text("Edit")
                    }
                }
                TextButton(onClick = { onAction(CollectionsAction.DuplicateList(list.id)) }) {
                    Text("Duplicate")
                }
                TextButton(onClick = { onAction(CollectionsAction.MoveList(list, -1)) }) {
                    Text("↑")
                }
                TextButton(onClick = { onAction(CollectionsAction.MoveList(list, 1)) }) {
                    Text("↓")
                }
                TextButton(
                    onClick = {
                        onDelete(
                            DeleteTarget(
                                action = CollectionsAction.DeleteList(list.id),
                                systemOwned = list.origin == CollectionOrigin.SYSTEM,
                            ),
                        )
                    },
                ) {
                    Text(if (list.origin == CollectionOrigin.SYSTEM) "Hide" else "Delete")
                }
            }
        }
    }
}

internal data class ListEditorState(
    val title: String,
    val sort: CatalogSort,
    val layoutType: String?,
    val providerId: String,
    val status: String?,
    val format: String?,
    val includeGenre: String,
    val excludeGenre: String,
    val includeTag: String,
    val excludeTag: String,
    val minScore: String,
    val minChapters: String,
    val minVolumes: String,
    val preservedQuery: QueryExpression?,
    val filtersEditable: Boolean,
) {
    fun toDraftOrNull(): CollectionListDraft? {
        if (title.isBlank()) return null

        val query = if (!filtersEditable) {
            preservedQuery
        } else {
            val predicates = buildList<QueryExpression> {
                status?.let {
                    add(
                        QueryExpression.Predicate(
                            QueryField.STATUS,
                            QueryOperator.EQUALS,
                            QueryValue.of(it),
                        ),
                    )
                }
                format?.let {
                    add(
                        QueryExpression.Predicate(
                            QueryField.WORK_TYPE,
                            QueryOperator.EQUALS,
                            QueryValue.of(it),
                        ),
                    )
                }
                includeGenre.trim().takeIf(String::isNotEmpty)?.let {
                    add(
                        QueryExpression.Predicate(
                            QueryField.GENRE,
                            QueryOperator.CONTAINS,
                            QueryValue.of(it),
                        ),
                    )
                }
                excludeGenre.trim().takeIf(String::isNotEmpty)?.let {
                    add(
                        QueryExpression.Not(
                            QueryExpression.Predicate(
                                QueryField.GENRE,
                                QueryOperator.CONTAINS,
                                QueryValue.of(it),
                            ),
                        ),
                    )
                }
                includeTag.trim().takeIf(String::isNotEmpty)?.let {
                    add(
                        QueryExpression.Predicate(
                            QueryField.TAG,
                            QueryOperator.CONTAINS,
                            QueryValue.of(it),
                        ),
                    )
                }
                excludeTag.trim().takeIf(String::isNotEmpty)?.let {
                    add(
                        QueryExpression.Not(
                            QueryExpression.Predicate(
                                QueryField.TAG,
                                QueryOperator.CONTAINS,
                                QueryValue.of(it),
                            ),
                        ),
                    )
                }
                minScore.toDoubleOrNull()?.let {
                    add(
                        QueryExpression.Predicate(
                            QueryField.SCORE,
                            QueryOperator.GREATER_OR_EQUAL,
                            QueryValue.of(it),
                        ),
                    )
                }
                minChapters.toLongOrNull()?.let {
                    add(
                        QueryExpression.Predicate(
                            QueryField.CHAPTER_COUNT,
                            QueryOperator.GREATER_OR_EQUAL,
                            QueryValue.of(it),
                        ),
                    )
                }
                minVolumes.toLongOrNull()?.let {
                    add(
                        QueryExpression.Predicate(
                            QueryField.VOLUME_COUNT,
                            QueryOperator.GREATER_OR_EQUAL,
                            QueryValue.of(it),
                        ),
                    )
                }
            }

            when (predicates.size) {
                0 -> null
                1 -> predicates.single()
                else -> QueryExpression.All(predicates)
            }
        }

        return CollectionListDraft(
            title = title.trim(),
            query = query,
            sort = sort,
            layoutType = layoutType,
            providerId = providerId,
        )
    }

    companion object {
        fun empty(): ListEditorState = ListEditorState(
            title = "",
            sort = CatalogSort.POPULARITY_DESC,
            layoutType = null,
            providerId = "kitsu",
            status = null,
            format = null,
            includeGenre = "",
            excludeGenre = "",
            includeTag = "",
            excludeTag = "",
            minScore = "",
            minChapters = "",
            minVolumes = "",
            preservedQuery = null,
            filtersEditable = true,
        )

        fun from(list: CollectionList): ListEditorState {
            val parsed = parseSimpleQuery(list.query)
            return ListEditorState(
                title = list.title,
                sort = list.sort,
                layoutType = list.layoutType,
                providerId = list.providerId,
                status = parsed?.status,
                format = parsed?.format,
                includeGenre = parsed?.includeGenre.orEmpty(),
                excludeGenre = parsed?.excludeGenre.orEmpty(),
                includeTag = parsed?.includeTag.orEmpty(),
                excludeTag = parsed?.excludeTag.orEmpty(),
                minScore = parsed?.minScore.orEmpty(),
                minChapters = parsed?.minChapters.orEmpty(),
                minVolumes = parsed?.minVolumes.orEmpty(),
                preservedQuery = if (parsed == null) list.query else null,
                filtersEditable = parsed != null,
            )
        }

        fun fromDraft(draft: CollectionListDraft): ListEditorState {
            val parsed = parseSimpleQuery(draft.query)
            return ListEditorState(
                title = draft.title,
                sort = draft.sort,
                layoutType = draft.layoutType,
                providerId = draft.providerId,
                status = parsed?.status,
                format = parsed?.format,
                includeGenre = parsed?.includeGenre.orEmpty(),
                excludeGenre = parsed?.excludeGenre.orEmpty(),
                includeTag = parsed?.includeTag.orEmpty(),
                excludeTag = parsed?.excludeTag.orEmpty(),
                minScore = parsed?.minScore.orEmpty(),
                minChapters = parsed?.minChapters.orEmpty(),
                minVolumes = parsed?.minVolumes.orEmpty(),
                preservedQuery = if (parsed == null) draft.query else null,
                filtersEditable = parsed != null,
            )
        }
    }
}

private data class ParsedSimpleQuery(
    val status: String? = null,
    val format: String? = null,
    val includeGenre: String? = null,
    val excludeGenre: String? = null,
    val includeTag: String? = null,
    val excludeTag: String? = null,
    val minScore: String? = null,
    val minChapters: String? = null,
    val minVolumes: String? = null,
)

private data class ParsedSimpleTerm(
    val predicate: QueryExpression.Predicate,
    val negated: Boolean,
)

private fun parseSimpleQuery(expression: QueryExpression?): ParsedSimpleQuery? {
    if (expression == null) return ParsedSimpleQuery()

    fun parseTerm(candidate: QueryExpression): ParsedSimpleTerm? {
        return when (candidate) {
            is QueryExpression.Predicate -> ParsedSimpleTerm(candidate, negated = false)
            is QueryExpression.Not -> {
                val predicate = candidate.expression as? QueryExpression.Predicate ?: return null
                ParsedSimpleTerm(predicate, negated = true)
            }
            else -> null
        }
    }

    val terms = when (expression) {
        is QueryExpression.Predicate,
        is QueryExpression.Not,
        -> listOf(parseTerm(expression) ?: return null)
        is QueryExpression.All -> expression.expressions.map { parseTerm(it) ?: return null }
        else -> return null
    }

    var result = ParsedSimpleQuery()
    val seen = mutableSetOf<Pair<QueryField, Boolean>>()

    for (term in terms) {
        val predicate = term.predicate
        if (!seen.add(predicate.field to term.negated)) return null
        val value = predicate.value

        result = when {
            !term.negated &&
                predicate.field == QueryField.STATUS &&
                predicate.operator == QueryOperator.EQUALS &&
                value is QueryValue.StringValue -> {
                result.copy(status = value.value)
            }

            !term.negated &&
                predicate.field == QueryField.WORK_TYPE &&
                predicate.operator == QueryOperator.EQUALS &&
                value is QueryValue.StringValue -> {
                result.copy(format = value.value)
            }

            predicate.field == QueryField.GENRE &&
                predicate.operator == QueryOperator.CONTAINS &&
                value is QueryValue.StringValue -> {
                if (term.negated) {
                    result.copy(excludeGenre = value.value)
                } else {
                    result.copy(includeGenre = value.value)
                }
            }

            predicate.field == QueryField.TAG &&
                predicate.operator == QueryOperator.CONTAINS &&
                value is QueryValue.StringValue -> {
                if (term.negated) {
                    result.copy(excludeTag = value.value)
                } else {
                    result.copy(includeTag = value.value)
                }
            }

            !term.negated &&
                predicate.field == QueryField.SCORE &&
                predicate.operator == QueryOperator.GREATER_OR_EQUAL -> {
                result.copy(minScore = numericText(value) ?: return null)
            }

            !term.negated &&
                predicate.field == QueryField.CHAPTER_COUNT &&
                predicate.operator == QueryOperator.GREATER_OR_EQUAL &&
                value is QueryValue.IntegerValue -> {
                result.copy(minChapters = value.value.toString())
            }

            !term.negated &&
                predicate.field == QueryField.VOLUME_COUNT &&
                predicate.operator == QueryOperator.GREATER_OR_EQUAL &&
                value is QueryValue.IntegerValue -> {
                result.copy(minVolumes = value.value.toString())
            }

            else -> return null
        }
    }

    return result
}

private fun numericText(value: QueryValue): String? = when (value) {
    is QueryValue.IntegerValue -> value.value.toString()
    is QueryValue.DoubleValue -> value.value.toString()
    else -> null
}

private sealed interface EditorDialog {
    data object CreateCollection : EditorDialog
    data class RenameCollection(val collection: TsuzukiCollection) : EditorDialog
    data class CreateFolder(
        val collectionId: String,
        val parentFolderId: String?,
    ) : EditorDialog
    data class RenameFolder(val folder: CollectionFolder) : EditorDialog
    data class CreateList(
        val collectionId: String,
        val folderId: String,
    ) : EditorDialog
    data class EditList(val list: CollectionList) : EditorDialog
}

internal data class DeleteTarget(
    val action: CollectionsAction,
    val systemOwned: Boolean,
)

private const val COLLECTION_LIST_HEADER_COUNT = 1
private const val COLLECTION_PREVIEW_FOLDER_COUNT = 3

internal val SUPPORTED_SORTS = listOf(
    CatalogSort.POPULARITY_DESC,
    CatalogSort.POPULARITY_ASC,
    CatalogSort.RATING_DESC,
    CatalogSort.RATING_ASC,
    CatalogSort.UPDATED_DESC,
)
