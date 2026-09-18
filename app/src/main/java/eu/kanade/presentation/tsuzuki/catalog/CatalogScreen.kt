package eu.kanade.presentation.tsuzuki.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.tachiyomi.ui.tsuzuki.catalog.CatalogScreenState
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Refresh
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.DiscoverFeed
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun CatalogScreen(
    state: CatalogScreenState,
    navigateUp: () -> Unit,
    onChangeSearchQuery: (String) -> Unit,
    onSearch: (String) -> Unit,
    onClickCloseSearch: () -> Unit,
    onSelectItem: (CatalogItem) -> Unit,
    onDismissPreview: () -> Unit,
    onRetry: () -> Unit,
    onAddToLibrary: (CatalogItem) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var searchOpen by remember { mutableStateOf(state.searchQuery.isNotEmpty()) }

    Scaffold(
        modifier = modifier,
        topBar = { scrollBehavior ->
            SearchToolbar(
                titleContent = { AppBarTitle("Catalog") },
                searchQuery = if (searchOpen) state.searchQuery else null,
                onChangeSearchQuery = { query ->
                    if (query != null) {
                        searchOpen = true
                        onChangeSearchQuery(query)
                    } else {
                        searchOpen = false
                        onClickCloseSearch()
                    }
                },
                onSearch = { query ->
                    onSearch(query)
                },
                onClickCloseSearch = {
                    searchOpen = false
                    onClickCloseSearch()
                },
                navigateUp = navigateUp,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            when (state) {
                is CatalogScreenState.Loading -> {
                    LoadingScreen()
                }
                is CatalogScreenState.Empty -> {
                    EmptyScreen(
                        message = state.message ?: "No items found",
                    )
                }
                is CatalogScreenState.Error -> {
                    EmptyScreen(
                        message = state.message ?: state.error?.message ?: "An error occurred",
                        actions = listOf(
                            EmptyScreenAction(
                                stringRes = MR.strings.action_retry,
                                icon = MaterialSymbols.Rounded.Refresh,
                                onClick = onRetry,
                            ),
                        ),
                    )
                }
                is CatalogScreenState.Degraded -> {
                    DiscoverView(
                        feed = state.discoverFeed,
                        degradedReason = state.reason,
                        onSelectItem = onSelectItem,
                    )
                }
                is CatalogScreenState.Success -> {
                    if (state.isSearching) {
                        SearchResultsView(
                            items = state.searchResults,
                            onSelectItem = onSelectItem,
                        )
                    } else if (state.discoverFeed != null) {
                        DiscoverView(
                            feed = state.discoverFeed,
                            degradedReason = null,
                            onSelectItem = onSelectItem,
                        )
                    }
                }
            }

            state.selectedItem?.let { item ->
                CatalogItemDetailSheet(
                    item = item,
                    libraryActionState = state.libraryActionState,
                    onAddToLibrary = { onAddToLibrary(item) },
                    onDismissRequest = onDismissPreview,
                )
            }
        }
    }
}

@Composable
private fun SearchResultsView(
    items: List<CatalogItem>,
    onSelectItem: (CatalogItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(
            items = items,
            key = { "${it.provider}:${it.providerId}" },
        ) { item ->
            CatalogItemCard(
                item = item,
                onClick = { onSelectItem(item) },
            )
        }
    }
}

@Composable
private fun DiscoverView(
    feed: DiscoverFeed,
    degradedReason: String?,
    onSelectItem: (CatalogItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        if (degradedReason != null) {
            item(key = "degraded_banner") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        text = degradedReason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        // Trending Section
        item(key = "trending_header") {
            Text(
                text = "Trending",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            )
        }

        item(key = "trending_content") {
            feed.trending.fold(
                onSuccess = { trendingPage ->
                    if (trendingPage.items.isEmpty()) {
                        Text(
                            text = "No trending items available",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    } else {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(
                                items = trendingPage.items,
                                key = { "trending:${it.provider}:${it.providerId}" },
                            ) { item ->
                                CatalogCompactCard(
                                    item = item,
                                    onClick = { onSelectItem(item) },
                                )
                            }
                        }
                    }
                },
                onFailure = { error ->
                    Text(
                        text = "Unable to load trending: ${error.message ?: "Unknown error"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                },
            )
        }

        // Popular Section
        item(key = "popular_header") {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Popular",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            )
        }

        feed.popular.fold(
            onSuccess = { popularPage ->
                if (popularPage.items.isEmpty()) {
                    item(key = "popular_empty") {
                        Text(
                            text = "No popular items available",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                } else {
                    items(
                        items = popularPage.items,
                        key = { "popular:${it.provider}:${it.providerId}" },
                    ) { item ->
                        CatalogItemCard(
                            item = item,
                            onClick = { onSelectItem(item) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }
            },
            onFailure = { error ->
                item(key = "popular_error") {
                    Text(
                        text = "Unable to load popular: ${error.message ?: "Unknown error"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            },
        )
    }
}
