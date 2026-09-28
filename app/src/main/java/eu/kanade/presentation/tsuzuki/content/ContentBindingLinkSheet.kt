package eu.kanade.presentation.tsuzuki.content

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.ui.tsuzuki.content.ContentBindingLinkState
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.source.model.ScoredSourceCandidate

/**
 * Add-on search results are discovery evidence, not chapter options. Only confirmed candidates
 * are linked, and the user may continue to another bounded batch without closing the sheet.
 */
@Composable
fun ContentBindingLinkSheet(
    state: ContentBindingLinkState,
    onSelectAddon: (AddonId) -> Unit,
    onConfirmCandidate: (ScoredSourceCandidate) -> Unit,
    onSearchMore: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Link a reading Add-on", style = MaterialTheme.typography.titleLarge)
            when (state) {
                ContentBindingLinkState.Idle, ContentBindingLinkState.Loading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is ContentBindingLinkState.Addons -> {
                    if (state.enabled.isEmpty()) {
                        Text("No enabled reading Add-ons with internal sources were found.")
                    } else {
                        Text("Choose an installed, enabled Add-on to search for this title.")
                        LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
                            items(state.enabled, key = { it.id.value }) { addon ->
                                ListItem(
                                    headlineContent = { Text(addon.displayName) },
                                    supportingContent = { Text(addon.versionName) },
                                    modifier = Modifier.clickable { onSelectAddon(addon.id) },
                                )
                            }
                        }
                    }
                }
                is ContentBindingLinkState.SearchResults -> {
                    Text("Searching ${state.addon.displayName}…")
                    if (state.isSearching) CircularProgressIndicator()

                    Text("Bindings resolved in this search: ${state.boundCount}")
                    if (state.existingBindingCount > 0) {
                        Text(
                            "Previously stored bindings: ${state.existingBindingCount}. " +
                                "This count is informational and does not confirm readable chapters.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (state.emptySourceCount > 0) {
                        Text("Sources with an empty search: ${state.emptySourceCount}")
                    }
                    if (state.noMatchSourceCount > 0) {
                        Text("Sources without a safe title match: ${state.noMatchSourceCount}")
                    }
                    if (state.failureCount > 0) {
                        Text(
                            "Sources that could not be searched: ${state.failureCount}",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                    if (state.confirmationCandidates.isNotEmpty()) {
                        Text(
                            "Confirm the exact edition before linking. Internal source and language " +
                                "are shown only as candidate provenance.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                            items(
                                state.confirmationCandidates,
                                key = { it.candidate.sourceId.toString() + ":" + it.candidate.sourceUrl },
                            ) { item ->
                                val candidate = item.candidate
                                ListItem(
                                    leadingContent = {
                                        MangaCover.Book(
                                            data = candidate.thumbnailUrl,
                                            contentDescription = candidate.title,
                                            modifier = Modifier.width(56.dp).padding(end = 4.dp),
                                        )
                                    },
                                    headlineContent = { Text(candidate.title) },
                                    supportingContent = {
                                        Column {
                                            Text(candidate.sourceName + " · " + candidate.language)
                                            candidate.author?.takeIf(String::isNotBlank)?.let { Text(it) }
                                            Text(
                                                candidate.sourceUrl,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    },
                                    modifier = Modifier.clickable(
                                        enabled = !state.isConfirming,
                                        onClick = { onConfirmCandidate(item) },
                                    ),
                                )
                            }
                        }
                    }

                    if (state.isConfirming) {
                        Text("Saving the selected edition…")
                        CircularProgressIndicator()
                    }
                    if (!state.isSearching && !state.isConfirming && state.remainingSourceCount > 0) {
                        TextButton(onClick = onSearchMore) {
                            Text("Search more sources (${state.remainingSourceCount} remaining)")
                        }
                    }
                    TextButton(onClick = onBack) { Text("Choose another Add-on") }
                }
                is ContentBindingLinkState.Error -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onBack) { Text("Choose another Add-on") }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("Done")
            }
        }
    }
}
