package eu.kanade.presentation.tsuzuki.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.ui.tsuzuki.library.CanonicalLibraryCardModel
import eu.kanade.tachiyomi.ui.tsuzuki.library.CanonicalLibraryReadingState
import eu.kanade.tachiyomi.ui.tsuzuki.library.CanonicalLibraryScreenState
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.FilterList
import mihon.icons.materialsymbols.rounded.MoreVert
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.library.model.LOCAL_LIBRARY_ORIGIN
import tachiyomi.domain.tsuzuki.model.LibraryStatus
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
    onStatusFilterChange: (LibraryStatus?) -> Unit = {},
    onOriginFilterChange: (String?) -> Unit = {},
    onProviderListFilterChange: (String?) -> Unit = {},
    onToggleFormatFilter: (CatalogItemFormat) -> Unit = {},
    onClearAdvancedFilters: () -> Unit = {},
    onRead: (CanonicalLibraryCardModel) -> Unit = {},
    onOpenItem: (CanonicalLibraryCardModel) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var categoryDialogItem by remember {
        mutableStateOf<CanonicalLibraryCardModel?>(null)
    }
    var filterSheetVisible by remember { mutableStateOf(false) }

    val successState = state as? CanonicalLibraryScreenState.Success
    val advancedFilterCount = successState?.filters?.let { filters ->
        (if (filters.origin != null) 1 else 0) +
            (if (filters.listKey != null) 1 else 0) +
            filters.formats.size +
            (if (filters.categoryId != null) 1 else 0)
    } ?: 0

    Scaffold(
        modifier = modifier,
        topBar = {
            SearchToolbar(
                titleContent = { AppBarTitle(title) },
                navigateUp = navigateUp,
                searchQuery = successState?.searchQuery,
                onChangeSearchQuery = onSearchQueryChange,
                actions = {
                    IconButton(onClick = { filterSheetVisible = true }) {
                        BadgedBox(
                            badge = {
                                if (advancedFilterCount > 0) {
                                    Badge { Text(advancedFilterCount.toString()) }
                                }
                            },
                        ) {
                            Icon(
                                imageVector = MaterialSymbols.Rounded.FilterList,
                                contentDescription = "Filter library",
                            )
                        }
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
                CanonicalLibraryScreenState.Loading -> LoadingScreen()
                is CanonicalLibraryScreenState.Success -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        CanonicalLibraryStatusFilters(
                            selectedStatus = state.filters.status,
                            onStatusFilterChange = onStatusFilterChange,
                        )
                        CanonicalLibraryActiveFilters(
                            state = state,
                            categories = categories,
                            onClearOrigin = { onOriginFilterChange(null) },
                            onClearProviderList = { onProviderListFilterChange(null) },
                            onToggleFormat = onToggleFormatFilter,
                            onClearCategory = { onCategoryFilterChange(null) },
                            onShowAll = { filterSheetVisible = true },
                        )
                        if (state.items.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            ) {
                                EmptyScreen(message = "No titles match the current library filters")
                            }
                        } else {
                            CanonicalLibraryList(
                                items = state.items,
                                selectedOrigin = state.filters.origin,
                                onUpdateStatus = onUpdateStatus,
                                onRemoveItem = onRemoveItem,
                                onChangeCategories = { categoryDialogItem = it },
                                onRead = onRead,
                                onOpenItem = onOpenItem,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }

    if (filterSheetVisible && successState != null) {
        CanonicalLibraryFilterSheet(
            state = successState,
            categories = categories,
            onDismiss = { filterSheetVisible = false },
            onOriginFilterChange = onOriginFilterChange,
            onProviderListFilterChange = onProviderListFilterChange,
            onToggleFormatFilter = onToggleFormatFilter,
            onCategoryFilterChange = onCategoryFilterChange,
            onClearAdvancedFilters = onClearAdvancedFilters,
        )
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
                onSetCategories(item.canonicalTitleId, include)
            },
        )
    }
}

@Composable
private fun CanonicalLibraryStatusFilters(
    selectedStatus: LibraryStatus?,
    onStatusFilterChange: (LibraryStatus?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selectedStatus == null,
            onClick = { onStatusFilterChange(null) },
            label = { Text("All") },
        )
        LibraryStatus.entries.forEach { status ->
            FilterChip(
                selected = selectedStatus == status,
                onClick = { onStatusFilterChange(status) },
                label = { Text(status.libraryLabel()) },
            )
        }
    }
}

private data class ActiveFilter(
    val label: String,
    val clear: () -> Unit,
)

@Composable
private fun CanonicalLibraryActiveFilters(
    state: CanonicalLibraryScreenState.Success,
    categories: List<Category>,
    onClearOrigin: () -> Unit,
    onClearProviderList: () -> Unit,
    onToggleFormat: (CatalogItemFormat) -> Unit,
    onClearCategory: () -> Unit,
    onShowAll: () -> Unit,
) {
    val filters = state.filters
    val active = buildList {
        filters.origin?.let { origin ->
            add(ActiveFilter(origin.libraryOriginLabel(), onClearOrigin))
        }
        filters.listKey?.let { listKey ->
            val title = state.availableProviderLists
                .firstOrNull { it.key == listKey }
                ?.title
                ?: listKey
            add(ActiveFilter(title, onClearProviderList))
        }
        filters.formats
            .sortedBy(CatalogItemFormat::ordinal)
            .forEach { format ->
                add(ActiveFilter(format.libraryFormatLabel()) { onToggleFormat(format) })
            }
        filters.categoryId?.let { categoryId ->
            val label = categories
                .firstOrNull { it.id == categoryId }
                ?.visualName
                ?: if (categoryId == Category.UNCATEGORIZED_ID) "Uncategorized" else "Category"
            add(ActiveFilter(label, onClearCategory))
        }
    }

    if (active.isEmpty()) return

    val visible = active.take(2)
    val hiddenCount = active.size - visible.size
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visible.forEach { filter ->
            SuggestionChip(
                onClick = filter.clear,
                label = { Text("${filter.label} ×") },
            )
        }
        if (hiddenCount > 0) {
            SuggestionChip(
                onClick = onShowAll,
                label = { Text("+$hiddenCount") },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CanonicalLibraryFilterSheet(
    state: CanonicalLibraryScreenState.Success,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onOriginFilterChange: (String?) -> Unit,
    onProviderListFilterChange: (String?) -> Unit,
    onToggleFormatFilter: (CatalogItemFormat) -> Unit,
    onCategoryFilterChange: (Long?) -> Unit,
    onClearAdvancedFilters: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Filter Library", style = MaterialTheme.typography.titleLarge)

            FilterSection("Origin") {
                FilterChip(
                    selected = state.filters.origin == null,
                    onClick = { onOriginFilterChange(null) },
                    label = { Text("All") },
                )
                state.availableOrigins
                    .sortedBy(String::libraryOriginLabel)
                    .forEach { origin ->
                        FilterChip(
                            selected = state.filters.origin == origin,
                            onClick = { onOriginFilterChange(origin) },
                            label = { Text(origin.libraryOriginLabel()) },
                        )
                    }
            }

            if (state.availableProviderLists.isNotEmpty()) {
                FilterSection("List") {
                    FilterChip(
                        selected = state.filters.listKey == null,
                        onClick = { onProviderListFilterChange(null) },
                        label = { Text("All") },
                    )
                    state.availableProviderLists.forEach { list ->
                        FilterChip(
                            selected = state.filters.listKey == list.key,
                            onClick = { onProviderListFilterChange(list.key) },
                            label = { Text(list.title) },
                        )
                    }
                }
            }

            FilterSection("Format") {
                state.availableFormats
                    .sortedBy(CatalogItemFormat::ordinal)
                    .forEach { format ->
                        FilterChip(
                            selected = format in state.filters.formats,
                            onClick = { onToggleFormatFilter(format) },
                            label = { Text(format.libraryFormatLabel()) },
                        )
                    }
            }

            FilterSection("Category") {
                FilterChip(
                    selected = state.filters.categoryId == null,
                    onClick = { onCategoryFilterChange(null) },
                    label = { Text("All") },
                )
                FilterChip(
                    selected = state.filters.categoryId == Category.UNCATEGORIZED_ID,
                    onClick = { onCategoryFilterChange(Category.UNCATEGORIZED_ID) },
                    label = { Text("Uncategorized") },
                )
                categories
                    .filter { it.id != Category.UNCATEGORIZED_ID }
                    .forEach { category ->
                        FilterChip(
                            selected = state.filters.categoryId == category.id,
                            onClick = { onCategoryFilterChange(category.id) },
                            label = { Text(category.visualName) },
                        )
                    }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        onClearAdvancedFilters()
                        onDismiss()
                    },
                ) {
                    Text("Clear filters")
                }
                TextButton(onClick = onDismiss) {
                    Text("Done")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun CanonicalLibraryList(
    items: List<CanonicalLibraryCardModel>,
    selectedOrigin: String?,
    onUpdateStatus: (String, LibraryStatus) -> Unit,
    onRemoveItem: (String) -> Unit,
    onChangeCategories: (CanonicalLibraryCardModel) -> Unit,
    onRead: (CanonicalLibraryCardModel) -> Unit,
    onOpenItem: (CanonicalLibraryCardModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(
            items = items,
            key = CanonicalLibraryCardModel::canonicalTitleId,
        ) { item ->
            MangaCover.Book(
                data = item.coverUrl ?: item.sourceCover ?: item.localCoverUrl,
                fallbackData = listOf(item.sourceCover, item.sourceCover?.url, item.localCoverUrl),
                contentDescription = item.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenItem(item) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CanonicalLibraryItemCard(
    item: CanonicalLibraryCardModel,
    selectedOrigin: String?,
    onUpdateStatus: (LibraryStatus) -> Unit,
    onRemove: () -> Unit,
    onChangeCategories: () -> Unit,
    onRead: () -> Unit,
    onOpenItem: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var statusMenuExpanded by remember { mutableStateOf(false) }
    val contextualStatuses = if (selectedOrigin == null) {
        item.originStatuses.values.flatten().toSet()
    } else {
        item.originStatuses[selectedOrigin].orEmpty()
    }.ifEmpty { setOf(item.status) }
    val canEditStatusFromChip = item.hasLocalMembership &&
        (selectedOrigin == LOCAL_LIBRARY_ORIGIN || (selectedOrigin == null && item.origins.size == 1))

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenItem),
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
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )

                if (item.hasLocalMembership) {
                    Box {
                        IconButton(onClick = { statusMenuExpanded = true }) {
                            Icon(
                                imageVector = MaterialSymbols.Rounded.MoreVert,
                                contentDescription = "Tsuzuki status options",
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
                                            text = status.libraryLabel(),
                                            fontWeight = if (status == item.status) {
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
                            contentDescription = "Remove from Tsuzuki Library",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SuggestionChip(
                    onClick = {
                        if (canEditStatusFromChip) {
                            statusMenuExpanded = true
                        }
                    },
                    enabled = canEditStatusFromChip,
                    label = {
                        Text(
                            text = "Status: ${contextualStatuses.joinToString(" · ") { it.libraryLabel() }}",
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
                if (item.origins.isNotEmpty()) {
                    SuggestionChip(
                        onClick = {},
                        enabled = false,
                        label = {
                            Text(
                                item.origins
                                    .sorted()
                                    .joinToString(" · ") { it.libraryOriginLabel() },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
                if (item.format != CatalogItemFormat.UNKNOWN) {
                    SuggestionChip(
                        onClick = {},
                        enabled = false,
                        label = {
                            Text(
                                item.format.libraryFormatLabel(),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
                SuggestionChip(
                    onClick = onRead,
                    label = {
                        Text(
                            text = when (item.readingState) {
                                CanonicalLibraryReadingState.NOT_STARTED -> "Not started"
                                CanonicalLibraryReadingState.IN_PROGRESS -> "In progress"
                                CanonicalLibraryReadingState.READ -> "Read"
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onRead) {
                    Text("Read / Continue")
                }
                TextButton(onClick = onOpenItem) {
                    Text("Details")
                }
            }
        }
    }
}

private fun LibraryStatus.libraryLabel(): String = when (this) {
    LibraryStatus.READING -> "Reading"
    LibraryStatus.PLANNING -> "Planning"
    LibraryStatus.COMPLETED -> "Completed"
    LibraryStatus.ON_HOLD -> "On Hold"
    LibraryStatus.DROPPED -> "Dropped"
}

private fun String.libraryOriginLabel(): String = when (this) {
    LOCAL_LIBRARY_ORIGIN -> "Tsuzuki"
    "mal" -> "MAL"
    "kitsu" -> "Kitsu"
    "mangaupdates" -> "MangaUpdates"
    "bangumi" -> "Bangumi"
    "shikimori" -> "Shikimori"
    "hikka" -> "Hikka"
    else -> replaceFirstChar { it.uppercase() }
}

private fun CatalogItemFormat.libraryFormatLabel(): String = when (this) {
    CatalogItemFormat.MANGA -> "Manga"
    CatalogItemFormat.NOVEL -> "Novel"
    CatalogItemFormat.ONE_SHOT -> "One-shot"
    CatalogItemFormat.MANHWA -> "Manhwa"
    CatalogItemFormat.MANHUA -> "Manhua"
    CatalogItemFormat.DOUJIN -> "Doujinshi"
    CatalogItemFormat.WEBTOON -> "Webtoon"
    CatalogItemFormat.UNKNOWN -> "Unknown"
}
