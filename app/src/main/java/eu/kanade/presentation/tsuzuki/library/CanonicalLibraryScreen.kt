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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.tachiyomi.ui.tsuzuki.library.CanonicalLibraryScreenState
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.MoreVert
import mihon.icons.materialsymbols.rounded.Settings
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
    onRead: (CanonicalLibraryItem) -> Unit = {},
    onResolveSource: (CanonicalLibraryItem) -> Unit = {},
    onOpenSourcePreferences: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
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
                    if (state.items.isEmpty()) {
                        EmptyScreen(
                            message = "No canonical titles in library",
                        )
                    } else {
                        CanonicalLibraryList(
                            items = state.items,
                            onUpdateStatus = onUpdateStatus,
                            onRemoveItem = onRemoveItem,
                            onRead = onRead,
                            onResolveSource = onResolveSource,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CanonicalLibraryList(
    items: List<CanonicalLibraryItem>,
    onUpdateStatus: (String, LibraryStatus) -> Unit,
    onRemoveItem: (String) -> Unit,
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
