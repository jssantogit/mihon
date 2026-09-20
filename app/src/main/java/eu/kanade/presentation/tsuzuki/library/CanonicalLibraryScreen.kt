package eu.kanade.presentation.tsuzuki.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.tachiyomi.ui.tsuzuki.library.CanonicalLibraryScreenState
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.MoreVert
import mihon.icons.materialsymbols.rounded.Settings
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.tsuzuki.library.model.CanonicalLibraryItem
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.model.SourceRepresentation
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun CanonicalLibraryScreen(
    state: CanonicalLibraryScreenState,
    navigateUp: (() -> Unit)? = null,
    title: String = "Library",
    onUpdateStatus: (String, LibraryStatus) -> Unit,
    onRemoveItem: (String) -> Unit,
    onSearchQueryChange: (String?) -> Unit = {},
    categories: List<Category> = emptyList(),
    onSetCategories: (String, List<Long>) -> Unit = { _, _ -> },
    onEditCategories: () -> Unit = {},
    onCategoryFilterChange: (Long?) -> Unit = {},
    onRead: (CanonicalLibraryItem) -> Unit = {},
    onResolveSource: (CanonicalLibraryItem) -> Unit = {},
    onOpenSourcePreferences: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var categoryDialogItem by remember { mutableStateOf<CanonicalLibraryItem?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            SearchToolbar(
                titleContent = { AppBarTitle(title) },
                navigateUp = navigateUp,
                searchQuery = (state as? CanonicalLibraryScreenState.Success)?.searchQuery,
                onChangeSearchQuery = onSearchQueryChange,
                actions = {
                    TextButton(onClick = onOpenSourcePreferences) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.Settings,
                            contentDescription = "Reading source preferences",
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            when (state) {
                is CanonicalLibraryScreenState.Loading -> {
                    LoadingScreen()
                }
                is CanonicalLibraryScreenState.Success -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        CanonicalLibraryCategoryFilters(
                            categories = categories,
                            selectedCategoryId = state.selectedCategoryId,
                            onCategoryFilterChange = onCategoryFilterChange,
                        )
                        if (state.items.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            ) {
                                EmptyScreen(
                                    message = "No canonical titles in library",
                                )
                            }
                        } else {
                            CanonicalLibraryList(
                                items = state.items,
                                onUpdateStatus = onUpdateStatus,
                                onRemoveItem = onRemoveItem,
                                onChangeCategories = { categoryDialogItem = it },
                                onRead = onRead,
                                onResolveSource = onResolveSource,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }

    categoryDialogItem?.let { item ->
        ChangeCategoryDialog(
            initialSelection = categories
                .filter { it.id != Category.UNCATEGORIZED_ID }
                .map { category ->
                    if (item.categories.any { it.id == category.id }) {
                        CheckboxState.State.Checked(category)
                    } else {
                        CheckboxState.State.None(category)
                    }
                },
            onDismissRequest = { categoryDialogItem = null },
            onEditCategories = {
                categoryDialogItem = null
                onEditCategories()
            },
            onConfirm = { include, _ ->
                categoryDialogItem = null
                onSetCategories(item.title.id, include)
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CanonicalLibraryCategoryFilters(
    categories: List<Category>,
    selectedCategoryId: Long?,
    onCategoryFilterChange: (Long?) -> Unit,
) {
    FlowRow(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilterChip(
            selected = selectedCategoryId == null,
            onClick = { onCategoryFilterChange(null) },
            label = { Text("All") },
        )
        categories.forEach { category ->
            FilterChip(
                selected = selectedCategoryId == category.id,
                onClick = { onCategoryFilterChange(category.id) },
                label = { Text(category.visualName) },
            )
        }
    }
}

@Composable
private fun CanonicalLibraryList(
    items: List<CanonicalLibraryItem>,
    onUpdateStatus: (String, LibraryStatus) -> Unit,
    onRemoveItem: (String) -> Unit,
    onChangeCategories: (CanonicalLibraryItem) -> Unit,
    onRead: (CanonicalLibraryItem) -> Unit,
    onResolveSource: (CanonicalLibraryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(
            items = items,
            key = { it.title.id },
        ) { item ->
            CanonicalLibraryItemCard(
                item = item,
                sources = item.sources,
                onUpdateStatus = { status -> onUpdateStatus(item.title.id, status) },
                onRemove = { onRemoveItem(item.title.id) },
                onChangeCategories = { onChangeCategories(item) },
                onRead = { onRead(item) },
                onResolveSource = { onResolveSource(item) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CanonicalLibraryItemCard(
    item: CanonicalLibraryItem,
    sources: List<SourceRepresentation>,
    onUpdateStatus: (LibraryStatus) -> Unit,
    onRemove: () -> Unit,
    onChangeCategories: () -> Unit,
    onRead: () -> Unit,
    onResolveSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var statusMenuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.title.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )

                Box {
                    IconButton(onClick = { statusMenuExpanded = true }) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.MoreVert,
                            contentDescription = "Status Options",
                        )
                    }

                    DropdownMenu(
                        expanded = statusMenuExpanded,
                        onDismissRequest = { statusMenuExpanded = false },
                    ) {
                        LibraryStatus.entries.forEach { status ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = status.name.lowercase().replaceFirstChar { it.uppercase() },
                                        fontWeight = if (status == item.entry.status) {
                                            FontWeight.Bold
                                        } else {
                                            FontWeight.Normal
                                        },
                                    )
                                },
                                onClick = {
                                    statusMenuExpanded = false
                                    onUpdateStatus(status)
                                },
                            )
                        }
                    }
                }

                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Delete,
                        contentDescription = "Remove from Library",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SuggestionChip(
                    onClick = { statusMenuExpanded = true },
                    label = {
                        Text(
                            text = "Status: ${item.entry.status.name.lowercase().replaceFirstChar { it.uppercase() }}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )

                SuggestionChip(
                    onClick = {},
                    label = {
                        Text(
                            text = "Identity: ${item.title.identityState.name.lowercase().replaceFirstChar {
                                it.uppercase()
                            }}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )

                SuggestionChip(
                    onClick = onChangeCategories,
                    label = {
                        Text(
                            text = if (item.categories.isEmpty()) {
                                "Categories: Uncategorized"
                            } else {
                                "Categories: ${item.categories.joinToString { it.name }}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }

            val preferredSource = sources.firstOrNull { it.preferredOverride } ?: sources.firstOrNull()
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onRead,
                    enabled = preferredSource != null,
                ) {
                    Text("Read / Continue")
                }

                TextButton(onClick = onResolveSource) {
                    if (preferredSource == null) {
                        Text("Find reading source")
                    } else {
                        Text(
                            "Reading source: #${preferredSource.sourceId} · ${preferredSource.language}",
                        )
                    }
                }
            }
        }
    }
}
