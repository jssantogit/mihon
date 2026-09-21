package eu.kanade.presentation.tsuzuki.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.tsuzuki.detail.CanonicalChapterDetailItem
import eu.kanade.tachiyomi.ui.tsuzuki.detail.CanonicalTitleScreenState
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation

@Composable
fun CanonicalTitleDetailScreen(
    state: CanonicalTitleScreenState,
    navigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onOpenChapter: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Title details") },
                navigationIcon = {
                    TextButton(onClick = navigateUp) {
                        Text("Back")
                    }
                },
                actions = {
                    TextButton(onClick = onRefresh) {
                        Text("Refresh")
                    }
                },
            )
        },
    ) { contentPadding ->
        when (state) {
            CanonicalTitleScreenState.Loading -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            is CanonicalTitleScreenState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = state.error.message ?: "Unable to load title.",
                    )
                    TextButton(onClick = onRefresh) {
                        Text("Retry")
                    }
                }
            }

            is CanonicalTitleScreenState.Loaded -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    item {
                        TitleHeader(state)
                    }
                    if (state.chapters.isEmpty()) {
                        item {
                            Text(
                                text = "No canonical chapters yet.",
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    } else {
                        items(
                            items = state.chapters,
                            key = { it.chapter.id },
                        ) { item ->
                            CanonicalChapterRow(
                                item = item,
                                onOpenChapter = onOpenChapter,
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TitleHeader(
    state: CanonicalTitleScreenState.Loaded,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = state.title.displayTitle,
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = state.libraryEntry
                ?.status
                ?.name
                ?.replace('_', ' ')
                ?: "Not in Library",
            style = MaterialTheme.typography.bodyMedium,
        )
        state.refreshError?.let {
            Text(
                text = "Chapter refresh is temporarily unavailable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun CanonicalChapterRow(
    item: CanonicalChapterDetailItem,
    onOpenChapter: (String) -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = "Chapter ${item.chapter.displayNumber}",
            )
        },
        supportingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (item.progress?.read == true) {
                        "Read"
                    } else {
                        "Unread"
                    },
                )
                if (item.downloaded) {
                    Text("Downloaded")
                }
            }
        },
        trailingContent = {
            when (item.confirmation) {
                CanonicalChapterConfirmation.PROVISIONAL -> {
                    AssistChip(
                        onClick = {},
                        label = { Text("Provisional") },
                    )
                }

                CanonicalChapterConfirmation.CONFLICTED -> {
                    AssistChip(
                        onClick = {},
                        label = { Text("Conflicted") },
                    )
                }

                CanonicalChapterConfirmation.CONFIRMED -> Unit
            }
        },
        modifier = Modifier.clickable {
            onOpenChapter(item.chapter.id)
        },
    )
}
