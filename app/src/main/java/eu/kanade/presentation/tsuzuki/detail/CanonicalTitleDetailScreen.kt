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
    onLinkReadingAddon: () -> Unit,
    onStartChapterDiagnostics: () -> Unit,
    onStopChapterDiagnostics: () -> Unit,
    onCopyChapterDiagnostics: () -> Unit,
    onClearChapterDiagnostics: () -> Unit,
    onAddToLibrary: () -> Unit,
    onRemoveFromLibrary: () -> Unit,
    onOpenChapter: (String) -> Unit,
    onDownloadChapter: (String) -> Unit,
    onOpenAddonsSettings: () -> Unit,
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
                            onStartChapterDiagnostics = onStartChapterDiagnostics,
                            onStopChapterDiagnostics = onStopChapterDiagnostics,
                            onCopyChapterDiagnostics = onCopyChapterDiagnostics,
                            onClearChapterDiagnostics = onClearChapterDiagnostics,
                            onAddToLibrary = onAddToLibrary,
                            onLinkReadingAddon = onLinkReadingAddon,
                            onRemoveFromLibrary = onRemoveFromLibrary,
                        )
                    }
                    if (state.chapters.isEmpty()) {
                        item {
                            Text(
                                text = if (state.isRefreshing) "Loading chapters…" else "No chapters found.",
                                modifier = Modifier.padding(16.dp),
                            )
                            if (!state.isRefreshing) {
                                TextButton(onClick = onOpenAddonsSettings) {
                                    Text("Manage reading Add-ons")
                                }
                            }
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
    onStartChapterDiagnostics: () -> Unit,
    onStopChapterDiagnostics: () -> Unit,
    onCopyChapterDiagnostics: () -> Unit,
    onClearChapterDiagnostics: () -> Unit,
    onAddToLibrary: () -> Unit,
    onRemoveFromLibrary: () -> Unit,
    onLinkReadingAddon: () -> Unit,
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
        if (state.isRefreshing) {
            Text(
                text = "Refreshing chapter data…",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = "Temporary chapter diagnostics",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = if (state.chapterDiagnosticsRecording) {
                "Chapter diagnostics recording in memory. Refresh chapters, copy the report, then stop or clear it."
            } else {
                "Temporary chapter diagnostics are off. Starting records the current list; then tap Refresh."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = if (state.chapterDiagnosticsRecording) {
                    onStopChapterDiagnostics
                } else {
                    onStartChapterDiagnostics
                },
            ) {
                Text(if (state.chapterDiagnosticsRecording) "Stop diagnostic" else "Start diagnostic")
            }
            TextButton(
                enabled = state.chapterDiagnosticReportAvailable,
                onClick = onCopyChapterDiagnostics,
            ) {
                Text("Copy report")
            }
            TextButton(
                enabled = state.chapterDiagnosticReportAvailable || state.chapterDiagnosticsRecording,
                onClick = onClearChapterDiagnostics,
            ) {
                Text("Clear")
            }
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
        TextButton(onClick = onLinkReadingAddon) {
            Text("Link alternative reading Add-on")
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
        state.chapterActionError?.let {
            Text(
                text = it.message ?: "Unable to open chapter.",
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
                    CanonicalChapterConfirmation.PROVISIONAL -> Unit

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
