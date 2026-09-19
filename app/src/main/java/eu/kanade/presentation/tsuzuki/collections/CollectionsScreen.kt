package eu.kanade.presentation.tsuzuki.collections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionFolderUiModel
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionListDraft
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionListRuntimeState
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionUiModel
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionsAction
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionsScreenState
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionsTransferState
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.collections.model.CollectionFolder
import tachiyomi.domain.tsuzuki.collections.model.CollectionList
import tachiyomi.domain.tsuzuki.collections.model.CollectionOrigin
import tachiyomi.domain.tsuzuki.collections.model.TsuzukiCollection
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsScreen(
    state: CollectionsScreenState,
    navigateUp: () -> Unit,
    onAction: (CollectionsAction) -> Unit,
    onImportRequest: () -> Unit,
    onExportRequest: () -> Unit,
    onDismissTransferState: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editor by remember { mutableStateOf<EditorDialog?>(null) }
    var deleteTarget by remember { mutableStateOf<DeleteTarget?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Collections") },
                navigationIcon = {
                    TextButton(onClick = navigateUp) {
                        Text("Back")
                    }
                },
                actions = {
                    TextButton(onClick = onImportRequest) {
                        Text("Import")
                    }
                    TextButton(onClick = onExportRequest) {
                        Text("Export")
                    }
                    TextButton(
                        onClick = { editor = EditorDialog.CreateCollection },
                    ) {
                        Text("New")
                    }
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

    editor?.let { dialog ->
        when (dialog) {
            EditorDialog.CreateCollection -> {
                TextEditorDialog(
                    title = "New Collection",
                    label = "Name",
                    initialValue = "",
                    onDismiss = { editor = null },
                    onConfirm = { title ->
                        onAction(CollectionsAction.CreateCollection(title))
                        editor = null
                    },
                )
            }

            is EditorDialog.RenameCollection -> {
                TextEditorDialog(
                    title = "Rename Collection",
                    label = "Name",
                    initialValue = dialog.collection.title,
                    onDismiss = { editor = null },
                    onConfirm = { title ->
                        onAction(CollectionsAction.RenameCollection(dialog.collection, title))
                        editor = null
                    },
                )
            }

            is EditorDialog.CreateFolder -> {
                TextEditorDialog(
                    title = if (dialog.parentFolderId == null) "New Folder" else "New Subfolder",
                    label = "Name",
                    initialValue = "",
                    onDismiss = { editor = null },
                    onConfirm = { title ->
                        onAction(
                            CollectionsAction.CreateFolder(
                                collectionId = dialog.collectionId,
                                parentFolderId = dialog.parentFolderId,
                                title = title,
                            ),
                        )
                        editor = null
                    },
                )
            }

            is EditorDialog.RenameFolder -> {
                TextEditorDialog(
                    title = "Rename Folder",
                    label = "Name",
                    initialValue = dialog.folder.title,
                    onDismiss = { editor = null },
                    onConfirm = { title ->
                        onAction(CollectionsAction.RenameFolder(dialog.folder, title))
                        editor = null
                    },
                )
            }

            is EditorDialog.CreateList -> {
                ListEditorDialog(
                    title = "New List",
                    initial = ListEditorState.empty(),
                    onDismiss = { editor = null },
                    onConfirm = { draft ->
                        onAction(
                            CollectionsAction.CreateList(
                                collectionId = dialog.collectionId,
                                folderId = dialog.folderId,
                                draft = draft,
                            ),
                        )
                        editor = null
                    },
                )
            }

            is EditorDialog.EditList -> {
                ListEditorDialog(
                    title = "Edit List",
                    initial = ListEditorState.from(dialog.list),
                    onDismiss = { editor = null },
                    onConfirm = { draft ->
                        onAction(CollectionsAction.UpdateList(dialog.list, draft))
                        editor = null
                    },
                )
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
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (collections.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = "No Collections yet. Create one to organize catalog queries.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        collections.forEach { graph ->
            item(key = "collection:${graph.collection.id}") {
                CollectionHeader(
                    graph = graph,
                    onAction = onAction,
                    onEdit = onEdit,
                    onDelete = onDelete,
                )
            }

            graph.folders.forEach { folder ->
                item(key = "folder:${folder.folder.id}") {
                    FolderRow(
                        collectionId = graph.collection.id,
                        model = folder,
                        onAction = onAction,
                        onEdit = onEdit,
                        onDelete = onDelete,
                    )
                }

                folder.lists.forEach { list ->
                    item(key = "list:${list.id}") {
                        ListRow(
                            folderDepth = folder.depth,
                            list = list,
                            runtimeState = listRuntimeStates[list.id] ?: CollectionListRuntimeState.Idle,
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
private fun CollectionHeader(
    graph: CollectionUiModel,
    onAction: (CollectionsAction) -> Unit,
    onEdit: (EditorDialog) -> Unit,
    onDelete: (DeleteTarget) -> Unit,
) {
    val collection = graph.collection
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = collection.title,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = if (collection.origin == CollectionOrigin.SYSTEM) "Built-in" else "User Collection",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (collection.origin == CollectionOrigin.USER) {
                    TextButton(
                        onClick = {
                            onEdit(
                                EditorDialog.CreateFolder(
                                    collectionId = collection.id,
                                    parentFolderId = null,
                                ),
                            )
                        },
                    ) {
                        Text("+ Folder")
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (collection.origin == CollectionOrigin.USER) {
                    TextButton(onClick = { onEdit(EditorDialog.RenameCollection(collection)) }) {
                        Text("Rename")
                    }
                }
                TextButton(onClick = { onAction(CollectionsAction.DuplicateCollection(collection.id)) }) {
                    Text("Duplicate")
                }
                TextButton(onClick = { onAction(CollectionsAction.MoveCollection(collection, -1)) }) {
                    Text("↑")
                }
                TextButton(onClick = { onAction(CollectionsAction.MoveCollection(collection, 1)) }) {
                    Text("↓")
                }
                TextButton(
                    onClick = {
                        onDelete(
                            DeleteTarget(
                                action = CollectionsAction.DeleteCollection(collection.id),
                                systemOwned = collection.origin == CollectionOrigin.SYSTEM,
                            ),
                        )
                    },
                ) {
                    Text(if (collection.origin == CollectionOrigin.SYSTEM) "Hide" else "Delete")
                }
            }
        }
    }
}

@Composable
private fun FolderRow(
    collectionId: String,
    model: CollectionFolderUiModel,
    onAction: (CollectionsAction) -> Unit,
    onEdit: (EditorDialog) -> Unit,
    onDelete: (DeleteTarget) -> Unit,
) {
    val folder = model.folder

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (model.depth * 20).dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (model.depth == 0) "Folder: ${folder.title}" else "↳ ${folder.title}",
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
                        runtimeState.items.forEach { item ->
                            Text(
                                text = "• ${item.title}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
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

@Composable
private fun TextEditorDialog(
    title: String,
    label: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = value.isNotBlank(),
                onClick = { onConfirm(value.trim()) },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
            )
        },
    )
}

@Composable
private fun ListEditorDialog(
    title: String,
    initial: ListEditorState,
    onDismiss: () -> Unit,
    onConfirm: (CollectionListDraft) -> Unit,
) {
    var editor by remember(initial) { mutableStateOf(initial) }
    val draft = editor.toDraftOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = draft != null,
                onClick = { draft?.let(onConfirm) },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = { Text(title) },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    OutlinedTextField(
                        value = editor.title,
                        onValueChange = { editor = editor.copy(title = it) },
                        label = { Text("List name") },
                        singleLine = true,
                    )
                }

                item {
                    OptionMenu(
                        label = "Sort",
                        current = editor.sort.name,
                        options = SUPPORTED_SORTS.map(CatalogSort::name),
                        onSelect = { selected ->
                            editor = editor.copy(sort = CatalogSort.valueOf(selected))
                        },
                    )
                }

                item {
                    OptionMenu(
                        label = "Layout",
                        current = editor.layoutType ?: "default",
                        options = listOf("default", "list", "grid"),
                        onSelect = { selected ->
                            editor = editor.copy(
                                layoutType = selected.takeUnless { it == "default" },
                            )
                        },
                    )
                }

                if (!editor.filtersEditable) {
                    item {
                        Text(
                            text = "This List contains an advanced imported query. " +
                                "Its query is preserved exactly, but this visual editor cannot modify those filters.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                } else {
                    item {
                        OptionMenu(
                            label = "Status",
                            current = editor.status ?: "Any",
                            options = listOf("Any", "ONGOING", "COMPLETED"),
                            onSelect = { selected ->
                                editor = editor.copy(status = selected.takeUnless { it == "Any" })
                            },
                        )
                    }

                    item {
                        OptionMenu(
                            label = "Work type",
                            current = editor.format ?: "Any",
                            options = listOf("Any") + CatalogItemFormat.entries
                                .filterNot { it == CatalogItemFormat.UNKNOWN }
                                .map(CatalogItemFormat::name),
                            onSelect = { selected ->
                                editor = editor.copy(format = selected.takeUnless { it == "Any" })
                            },
                        )
                    }

                    item {
                        NumericField(
                            label = "Minimum score",
                            value = editor.minScore,
                            onValueChange = { editor = editor.copy(minScore = it) },
                            allowDecimal = true,
                        )
                    }

                    item {
                        NumericField(
                            label = "Minimum chapters",
                            value = editor.minChapters,
                            onValueChange = { editor = editor.copy(minChapters = it) },
                        )
                    }

                    item {
                        NumericField(
                            label = "Minimum volumes",
                            value = editor.minVolumes,
                            onValueChange = { editor = editor.copy(minVolumes = it) },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun OptionMenu(
    label: String,
    current: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
        )
        TextButton(onClick = { expanded = true }) {
            Text(current)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun NumericField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    allowDecimal: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { candidate ->
            val valid = if (allowDecimal) {
                candidate.isEmpty() || candidate.toDoubleOrNull() != null
            } else {
                candidate.isEmpty() || candidate.toLongOrNull() != null
            }
            if (valid) onValueChange(candidate)
        },
        label = { Text(label) },
        singleLine = true,
    )
}

internal data class ListEditorState(
    val title: String,
    val sort: CatalogSort,
    val layoutType: String?,
    val status: String?,
    val format: String?,
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
        )
    }

    companion object {
        fun empty(): ListEditorState = ListEditorState(
            title = "",
            sort = CatalogSort.POPULARITY_DESC,
            layoutType = null,
            status = null,
            format = null,
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
                status = parsed?.status,
                format = parsed?.format,
                minScore = parsed?.minScore.orEmpty(),
                minChapters = parsed?.minChapters.orEmpty(),
                minVolumes = parsed?.minVolumes.orEmpty(),
                preservedQuery = if (parsed == null) list.query else null,
                filtersEditable = parsed != null,
            )
        }
    }
}

private data class ParsedSimpleQuery(
    val status: String? = null,
    val format: String? = null,
    val minScore: String? = null,
    val minChapters: String? = null,
    val minVolumes: String? = null,
)

private fun parseSimpleQuery(expression: QueryExpression?): ParsedSimpleQuery? {
    if (expression == null) return ParsedSimpleQuery()

    val predicates = when (expression) {
        is QueryExpression.Predicate -> listOf(expression)
        is QueryExpression.All -> expression.expressions.map {
            it as? QueryExpression.Predicate ?: return null
        }
        else -> return null
    }

    var result = ParsedSimpleQuery()
    val seen = mutableSetOf<QueryField>()

    for (predicate in predicates) {
        if (!seen.add(predicate.field)) return null
        val value = predicate.value

        result = when {
            predicate.field == QueryField.STATUS &&
                predicate.operator == QueryOperator.EQUALS &&
                value is QueryValue.StringValue -> {
                result.copy(status = value.value)
            }

            predicate.field == QueryField.WORK_TYPE &&
                predicate.operator == QueryOperator.EQUALS &&
                value is QueryValue.StringValue -> {
                result.copy(format = value.value)
            }

            predicate.field == QueryField.SCORE &&
                predicate.operator == QueryOperator.GREATER_OR_EQUAL -> {
                result.copy(minScore = numericText(value) ?: return null)
            }

            predicate.field == QueryField.CHAPTER_COUNT &&
                predicate.operator == QueryOperator.GREATER_OR_EQUAL &&
                value is QueryValue.IntegerValue -> {
                result.copy(minChapters = value.value.toString())
            }

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

private data class DeleteTarget(
    val action: CollectionsAction,
    val systemOwned: Boolean,
)

private val SUPPORTED_SORTS = listOf(
    CatalogSort.POPULARITY_DESC,
    CatalogSort.POPULARITY_ASC,
    CatalogSort.RATING_DESC,
    CatalogSort.RATING_ASC,
    CatalogSort.UPDATED_DESC,
)
