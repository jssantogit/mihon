package eu.kanade.presentation.tsuzuki.content

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
 * An explicit choice is required when several editions match the same canonical title.
 * Candidates from different editions are never silently merged by display title.
 */
@Composable
fun ContentBindingLinkSheet(
    state: ContentBindingLinkState,
    onSelectAddon: (AddonId) -> Unit,
    onConfirmCandidate: (ScoredSourceCandidate) -> Unit,
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
                        Text("Choose the Add-on that contains this title.")
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
                is ContentBindingLinkState.Searching -> {
                    Text("Searching ${state.addonName}…")
                    CircularProgressIndicator()
                }
                is ContentBindingLinkState.Candidates -> {
                    Text(
                        "Several editions match your title in ${state.addon.displayName}. " +
                            "Choose the exact edition you want to link.",
                    )
                    Text(
                        "This will not merge titles or change your reading progress.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
                        items(
                            state.candidates,
                            key = { it.candidate.sourceId.toString() + ":" + it.candidate.sourceUrl },
                        ) { item ->
                            val candidate = item.candidate
                            ListItem(
                                leadingContent = {
                                    MangaCover.Book(
                                        data = candidate.thumbnailUrl,
                                        contentDescription = candidate.title,
                                        modifier = Modifier.padding(end = 4.dp),
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
                                modifier = Modifier.clickable { onConfirmCandidate(item) },
                            )
                        }
                    }
                    TextButton(onClick = onBack) { Text("Choose another Add-on") }
                }
                is ContentBindingLinkState.Linked -> {
                    Text("${state.addonName} linked. Refreshing chapter alternatives…")
                }
                is ContentBindingLinkState.Error -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onBack) { Text("Choose another Add-on") }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("Close")
            }
        }
    }
}
