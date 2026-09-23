package eu.kanade.presentation.tsuzuki.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.tsuzuki.search.DiscoverBlock
import eu.kanade.tachiyomi.ui.tsuzuki.search.DiscoverKind
import eu.kanade.tachiyomi.ui.tsuzuki.search.SearchState
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat

@Composable
fun TsuzukiSearchScreen(
    state: SearchState,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onRecentSearch: (String) -> Unit,
    onResultClick: (CatalogItem) -> Unit,
    onClearRecent: () -> Unit,
    onRetry: () -> Unit,
    onOpenIntegrations: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Search") },
                actions = {
                    TextButton(onClick = onOpenIntegrations) {
                        Text("Integrations")
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                label = { Text("Search manga") },
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(
                    onSearch = { onSearch(query) },
                ),
            )

            when (state) {
                SearchState.Loading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is SearchState.NeedsIntegration -> {
                    NeedsIntegrationContent(
                        recentSearches = state.recentSearches,
                        onRecentSearch = onRecentSearch,
                        onOpenIntegrations = onOpenIntegrations,
                    )
                }

                is SearchState.Discover -> {
                    DiscoverContent(
                        state = state,
                        onRecentSearch = onRecentSearch,
                        onClearRecent = onClearRecent,
                        onResultClick = onResultClick,
                    )
                }

                is SearchState.Results -> {
                    CatalogItems(
                        items = state.items,
                        onResultClick = onResultClick,
                    )
                }

                is SearchState.Empty -> {
                    MessageContent(
                        message = "No results for “${state.query}”.",
                    )
                }

                is SearchState.Error -> {
                    MessageContent(
                        message = state.error.message ?: "Search failed.",
                        action = "Retry",
                        onAction = {
                            val retryQuery = state.query
                            if (retryQuery == null) {
                                onRetry()
                            } else {
                                onSearch(retryQuery)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NeedsIntegrationContent(
    recentSearches: List<String>,
    onRecentSearch: (String) -> Unit,
    onOpenIntegrations: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (recentSearches.isNotEmpty()) {
            item {
                RecentSearches(
                    searches = recentSearches,
                    onSearch = onRecentSearch,
                    onClear = null,
                )
            }
        }
        item {
            Text(
                text = "Enable Kitsu or MyAnimeList to search the catalog.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        item {
            Button(onClick = onOpenIntegrations) {
                Text("Configure integrations")
            }
        }
    }
}

@Composable
private fun DiscoverContent(
    state: SearchState.Discover,
    onRecentSearch: (String) -> Unit,
    onClearRecent: () -> Unit,
    onResultClick: (CatalogItem) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        if (state.recentSearches.isNotEmpty()) {
            item {
                RecentSearches(
                    searches = state.recentSearches,
                    onSearch = onRecentSearch,
                    onClear = onClearRecent,
                )
            }
        }
        items(
            items = state.blocks,
            key = DiscoverBlock::kind,
        ) { block ->
            DiscoverSection(
                block = block,
                onResultClick = onResultClick,
            )
        }
    }
}

@Composable
private fun RecentSearches(
    searches: List<String>,
    onSearch: (String) -> Unit,
    onClear: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = "Recent searches",
                style = MaterialTheme.typography.titleMedium,
            )
            if (onClear != null) {
                TextButton(onClick = onClear) {
                    Text("Clear")
                }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(
                items = searches,
                key = { it },
            ) { search ->
                AssistChip(
                    onClick = { onSearch(search) },
                    label = { Text(search) },
                )
            }
        }
    }
}

@Composable
private fun DiscoverSection(
    block: DiscoverBlock,
    onResultClick: (CatalogItem) -> Unit,
) {
    Column(
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        Text(
            text = when (block.kind) {
                DiscoverKind.TRENDING -> "Trending"
                DiscoverKind.POPULAR -> "Popular"
                DiscoverKind.RECENTLY_UPDATED -> "Recently updated"
            },
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        if (block.items.isEmpty()) {
            Text(
                text = "No items available.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = block.items,
                    key = { "${it.provider}:${it.providerId}" },
                ) { item ->
                    Card(
                        modifier = Modifier
                            .fillParentMaxWidth(0.72f)
                            .clickable { onResultClick(item) },
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            ProviderProvenance(
                                item = item,
                                peers = block.items,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogItems(
    items: List<CatalogItem>,
    onResultClick: (CatalogItem) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
    ) {
        items(
            items = items,
            key = { "${it.provider}:${it.providerId}" },
        ) { item ->
            ListItem(
                headlineContent = { Text(item.title) },
                supportingContent = {
                    ProviderProvenance(
                        item = item,
                        peers = items,
                    )
                },
                modifier = Modifier.clickable {
                    onResultClick(item)
                },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun ProviderProvenance(
    item: CatalogItem,
    peers: List<CatalogItem>,
) {
    val needsDisambiguation = peers.count {
        it.title.equals(item.title, ignoreCase = true)
    } > 1
    if (needsDisambiguation) {
        val provenance = buildList {
            add(item.provider)
            if (item.format != CatalogItemFormat.UNKNOWN) {
                add(item.format.name.lowercase().replace('_', ' '))
            }
            item.startDate
                ?.takeIf { it.length >= 4 }
                ?.take(4)
                ?.let(::add)
        }.joinToString(" · ")
        Text(
            text = provenance,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun MessageContent(
    message: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(action)
            }
        }
    }
}
