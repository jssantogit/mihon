package eu.kanade.presentation.tsuzuki.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tachiyomi.domain.tsuzuki.sync.model.StoredSyncConflict
import tachiyomi.domain.tsuzuki.sync.model.SyncConflict
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictResolutionChoice
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictValue

@Composable
fun SyncConflictResolutionScreen(
    conflicts: List<StoredSyncConflict>,
    resolvingConflictKey: String?,
    onResolve: (StoredSyncConflict, SyncConflictResolutionChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        conflicts.forEach { stored ->
            val conflict = stored.conflict
            val key = conflict.key()
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = conflict.documentKind.name,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = buildString {
                            append(conflict.recordId)
                            if (conflict.propertyPath.isNotEmpty()) {
                                append(" • ")
                                append(conflict.propertyPath.joinToString("."))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Conflict: ${conflict.kind.name}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        text = "Local: ${conflict.local.displayText()}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Remote: ${conflict.remote.displayText()}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = resolvingConflictKey == null,
                            onClick = {
                                onResolve(
                                    stored,
                                    SyncConflictResolutionChoice.KEEP_LOCAL,
                                )
                            },
                        ) {
                            Text(
                                if (resolvingConflictKey == key) {
                                    "Resolving…"
                                } else {
                                    "Keep local"
                                },
                            )
                        }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = resolvingConflictKey == null,
                            onClick = {
                                onResolve(
                                    stored,
                                    SyncConflictResolutionChoice.KEEP_REMOTE,
                                )
                            },
                        ) {
                            Text(
                                if (resolvingConflictKey == key) {
                                    "Resolving…"
                                } else {
                                    "Keep remote"
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun SyncConflictValue.displayText(): String = when (this) {
    SyncConflictValue.Missing -> "(missing)"
    SyncConflictValue.Tombstone -> "(deleted)"
    is SyncConflictValue.Present -> value.toString()
}

private fun SyncConflict.key(): String {
    return remoteConflictId?.let { "remote:$it" }
        ?: listOf(
            documentKind.name,
            recordId,
            propertyPath.joinToString("/"),
            kind.name,
        ).joinToString("|")
}
