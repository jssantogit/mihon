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
    onAddToLibrary: () -> Unit,
    onRemoveFromLibrary: () -> Unit,
    onOpenChapter: (String) -> Unit,
    onDownloadChapter: (String) -> Unit,
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
                        TitleHeader(
                            state = state,
                            onAddToLibrary = onAddToLibrary,
                            onRemoveFromLibrary = onRemoveFromLibrary,
                        )
                    }
                    if (state.chapters.isEmpty()) {
                        item {
                            val knownCounts = state.reportedChapterCounts
                                .filter { it.chapterCount != null }
                            Text(
                                text = if (knownCounts.isEmpty()) {
                                    if (state.isRefreshing) {
                                        "Loading chapter evidence…"
                                    } else {
                                        "No canonical chapters yet."
                                    }
                                } else {
                                    knownCounts.joinToString(
                                        prefix = "Reported chapter count: ",
                                        separator = " · ",
                                        postfix = ". Chapter structure is not available yet.",
                                    ) { count ->
                                        "${providerLabel(count.provider)}: ${count.chapterCount}"
                                    }
                                },
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
                                downloading = state.downloadInProgressChapterId == item.chapter.id,
                                onOpenChapter = onOpenChapter,
                                onDownloadChapter = onDownloadChapter,
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
    onAddToLibrary: () -> Unit,
    onRemoveFromLibrary: () -> Unit,
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
        val knownCounts = state.reportedChapterCounts.filter { it.chapterCount != null }
        if (knownCounts.isNotEmpty()) {
            Text(
                text = knownCounts.joinToString(separator = " · ") { count ->
                    "${providerLabel(count.provider)}: ${count.chapterCount} chapters"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.isRefreshing) {
            Text(
                text = "Refreshing chapter data…",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = state.libraryEntry
                ?.status
                ?.name
                ?.replace('_', ' ')
                ?: "Not in Library",
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(
            enabled = !state.libraryMutationInProgress,
            onClick = if (state.libraryEntry == null) {
                onAddToLibrary
            } else {
                onRemoveFromLibrary
            },
        ) {
            Text(
                if (state.libraryEntry == null) {
                    "Add to Library"
                } else {
                    "Remove from Library"
                },
            )
        }
        state.libraryMutationError?.let {
            Text(
                text = it.message ?: "Unable to update Library.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        state.refreshError?.let {
            Text(
                text = "Chapter refresh is temporarily unavailable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        state.downloadError?.let {
            Text(
                text = it.message ?: "Unable to download chapter.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun CanonicalChapterRow(
    item: CanonicalChapterDetailItem,
    downloading: Boolean,
    onOpenChapter: (String) -> Unit,
    onDownloadChapter: (String) -> Unit,
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
            Column(
                horizontalAlignment = Alignment.End,
            ) {
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
                TextButton(
                    enabled = !item.downloaded && !downloading,
                    onClick = { onDownloadChapter(item.chapter.id) },
                ) {
                    Text(
                        when {
                            item.downloaded -> "Downloaded"
                            downloading -> "Downloading…"
                            else -> "Download"
                        },
                    )
                }
            }
        },
        modifier = Modifier.clickable {
            onOpenChapter(item.chapter.id)
        },
    )
}

private fun providerLabel(provider: String): String = when (provider.lowercase()) {
    "kitsu" -> "Kitsu"
    "mal" -> "MyAnimeList"
    else -> provider
}
