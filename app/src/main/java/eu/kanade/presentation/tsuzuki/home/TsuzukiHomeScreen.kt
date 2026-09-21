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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.tachiyomi.ui.tsuzuki.home.TsuzukiHomeScreenState
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.home.model.HomeContinueReadingItem
import tachiyomi.domain.tsuzuki.home.model.HomeRow
import tachiyomi.domain.tsuzuki.home.model.HomeRowContent
import tachiyomi.domain.tsuzuki.home.model.HomeSection
import tachiyomi.presentation.core.components.material.Scaffold

@Composable
fun TsuzukiHomeScreen(
    state: TsuzukiHomeScreenState,
    onContinueReading: (HomeContinueReadingItem) -> Unit,
    onRemoveFromContinueReading: (HomeContinueReadingItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            AppBar(
                titleContent = { AppBarTitle("Home") },
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
            if (state.continueReading.isNotEmpty()) {
                item(key = "continue_header") {
                    SectionHeader("Continue Reading")
                }
                item(key = "continue_content") {
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
                                onRemove = { onRemoveFromContinueReading(item) },
                            )
                        }
                    }
                }
            }

            state.sections.forEach { section ->
                when (section) {
                    is HomeSection.CollectionSection -> {
                        item(key = "collection_header_${section.collectionId}") {
                            SectionHeader(section.title)
                        }
                        if (section.rows.isEmpty()) {
                            item(key = "collection_empty_${section.collectionId}") {
                                SectionMessage("No Home rows configured")
                            }
                        } else {
                            section.rows.forEach { row ->
                                item(key = "row_${row.listId}") {
                                    CollectionRow(row)
                                }
                            }
                        }
                    }
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
        modifier = Modifier.padding(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp,
            bottom = 4.dp,
        ),
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
    onRemove: () -> Unit,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                if (item.newChapterCount > 0) {
                    Text(
                        text = "+${item.newChapterCount}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = "Chapter ${item.chapterDisplayNumber}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Page ${item.lastPageRead + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRemove) {
                Text("Remove from Continue Reading")
            }
        }
    }
}

@Composable
private fun CollectionRow(row: HomeRow) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = row.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        when (val content = row.content) {
            is HomeRowContent.Content -> {
                if (content.items.isEmpty()) {
                    SectionMessage("No matching titles")
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(
                            items = content.items,
                            key = { "${it.provider}:${it.providerId}" },
                        ) { item ->
                            CatalogHomeCard(item)
                        }
                    }
                }
            }
            is HomeRowContent.Unavailable -> {
                SectionMessage(content.reason)
            }
        }
    }
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
