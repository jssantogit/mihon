package eu.kanade.presentation.tsuzuki.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.tachiyomi.ui.tsuzuki.home.TsuzukiHomeScreenState
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Refresh
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.home.model.HomeContinueReadingItem
import tachiyomi.presentation.core.components.material.Scaffold

@Composable
fun TsuzukiHomeScreen(
    state: TsuzukiHomeScreenState,
    onRefresh: () -> Unit,
    onContinueReading: (HomeContinueReadingItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            AppBar(
                titleContent = { AppBarTitle("Home") },
                actions = {
                    IconButton(
                        onClick = onRefresh,
                        enabled = !state.isRefreshing,
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.Refresh,
                            contentDescription = "Refresh home",
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "continue_header") {
                SectionHeader("Continue Reading")
            }

            item(key = "continue_content") {
                if (state.continueReading.isEmpty()) {
                    SectionMessage("Nothing in progress yet")
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(
                            items = state.continueReading,
                            key = { it.canonicalTitleId },
                        ) { item ->
                            ContinueReadingCard(
                                item = item,
                                onClick = { onContinueReading(item) },
                            )
                        }
                    }
                }
            }

            val feed = state.catalogFeed
            if (feed == null) {
                item(key = "remote_loading") {
                    SectionMessage("Loading catalog sections…")
                }
            } else {
                item(key = "recent_header") {
                    SectionHeader("Recently Updated")
                }
                item(key = "recent_content") {
                    CatalogSection(
                        result = feed.recentlyUpdated,
                        emptyMessage = "No recent titles available",
                    )
                }

                item(key = "trending_header") {
                    SectionHeader("Trending")
                }
                item(key = "trending_content") {
                    CatalogSection(
                        result = feed.trending,
                        emptyMessage = "No trending titles available",
                    )
                }

                item(key = "popular_header") {
                    SectionHeader("Popular")
                }
                item(key = "popular_content") {
                    CatalogSection(
                        result = feed.popular,
                        emptyMessage = "No popular titles available",
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SectionMessage(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ContinueReadingCard(
    item: HomeContinueReadingItem,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .width(220.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
            )
            Text(
                text = "Chapter ${item.chapterDisplayNumber}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Page ${item.lastPageRead + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CatalogSection(
    result: Result<CatalogPage>,
    emptyMessage: String,
) {
    result.fold(
        onSuccess = { page ->
            if (page.items.isEmpty()) {
                SectionMessage(emptyMessage)
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        items = page.items,
                        key = { "${it.provider}:${it.providerId}" },
                    ) { item ->
                        CatalogHomeCard(item)
                    }
                }
            }
        },
        onFailure = { error ->
            SectionMessage(error.message ?: "This section is temporarily unavailable")
        },
    )
}

@Composable
private fun CatalogHomeCard(item: CatalogItem) {
    Card(
        modifier = Modifier.width(180.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
            )
            item.score?.let { score ->
                Text(
                    text = "${score.value}/${score.maxValue}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = item.format.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = item.status.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
