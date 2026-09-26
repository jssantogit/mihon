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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.ui.tsuzuki.content.ContentOptionPresentation
import eu.kanade.tachiyomi.ui.tsuzuki.content.ContentSelectorScreenState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.text.DateFormat
import java.util.Date

/** Search can be offered only when the selected chapter has no real reading option. */
internal fun ContentSelectorScreenState.sourceDiscoveryTitleId(): String? = when (this) {
    ContentSelectorScreenState.Loading -> null
    is ContentSelectorScreenState.Discovering -> this.canonicalTitleId
    is ContentSelectorScreenState.Ready -> this.canonicalTitleId.takeIf { options.isEmpty() }
    is ContentSelectorScreenState.Empty -> this.canonicalTitleId
    is ContentSelectorScreenState.Error -> this.canonicalTitleId
}

internal fun ContentSelectorScreenState.shouldOfferSourceDiscovery(): Boolean =
    sourceDiscoveryTitleId() != null

internal fun sourceDiscoveryAction(
    state: ContentSelectorScreenState,
    onFindOrAddSource: ((canonicalTitleId: String) -> Unit)?,
): (() -> Unit)? {
    val canonicalTitleId = state.sourceDiscoveryTitleId() ?: return null
    val action = onFindOrAddSource ?: return null
    return { action(canonicalTitleId) }
}

/** Existing readable options must not hide the ability to add other languages. */
internal fun additionalSourcesAction(
    state: ContentSelectorScreenState,
    onFindOrAddSource: ((canonicalTitleId: String) -> Unit)?,
): (() -> Unit)? {
    val ready = state as? ContentSelectorScreenState.Ready ?: return null
    if (ready.options.isEmpty()) return null
    val action = onFindOrAddSource ?: return null
    return { action(ready.canonicalTitleId) }
}

@Composable
fun ContentOptionSelectorSheet(
    state: ContentSelectorScreenState,
    onSelect: (ContentOptionPresentation) -> Unit,
    activeOptionKey: String? = null,
    activeContentLabel: String? = null,
    onRetry: () -> Unit,
    onOpenAddonsSettings: () -> Unit,
    onFindOrAddSource: ((canonicalTitleId: String) -> Unit)? = null,
    onCancelDiscovery: (() -> Unit)? = null,
    onDismissRequest: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(MR.strings.tsuzuki_content_selector_title),
                style = MaterialTheme.typography.titleLarge,
            )
            activeContentLabel?.takeIf(String::isNotBlank)?.let { label ->
                Text(
                    text = "Reading now: $label",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (state) {
                ContentSelectorScreenState.Loading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is ContentSelectorScreenState.Discovering -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator()
                            Text("Finding available chapters in up to ${state.addonCount} reading Add-ons…")
                        }
                        if (state.failedAttempts > 0) {
                            Text(
                                "${state.failedAttempts} source attempt(s) could not finish. " +
                                    "Other sources are still being checked.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.confirmationRequired) {
                            Text(
                                "A possible edition needs your confirmation before it can be used.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            onCancelDiscovery?.let { onCancel ->
                                OutlinedButton(onClick = onCancel) { Text("Stop searching") }
                            }
                        }
                        sourceDiscoveryAction(state, onFindOrAddSource)?.let { onClick ->
                            Button(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
                                Text("Choose a reading Add-on")
                            }
                        }
                    }
                }

                is ContentSelectorScreenState.Ready -> {
                    if (state.shouldOfferSourceDiscovery()) {
                        SelectorUnavailableContent(
                            message = stringResource(MR.strings.tsuzuki_content_no_options),
                            onRetry = onRetry,
                            onOpenAddonsSettings = onOpenAddonsSettings,
                            onFindOrAddSource = sourceDiscoveryAction(state, onFindOrAddSource),
                        )
                    } else {
                        if (state.failedProviderCount > 0) {
                            Text(
                                text = "${state.failedProviderCount} reading Add-on(s) could not be queried. " +
                                    "Available alternatives are shown below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 480.dp),
                        ) {
                            items(
                                items = state.options,
                                key = { it.option.key },
                            ) { item ->
                                ContentOptionRow(
                                    item = item,
                                    preferred = state.preferredOptionKey == item.option.key,
                                    active = activeOptionKey == item.option.key,
                                    onClick = { onSelect(item) },
                                )
                            }
                        }
                        additionalSourcesAction(state, onFindOrAddSource)?.let { onClick ->
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onClick,
                            ) {
                                Text("Find more reading sources")
                            }
                        }
                    }
                }

                is ContentSelectorScreenState.Empty -> {
                    SelectorUnavailableContent(
                        message = when {
                            state.noEnabledAddon ->
                                "No reading Add-ons are enabled. Enable or install one to discover chapters."
                            state.confirmationRequired ->
                                "Possible editions were found, but you must confirm the correct one. Choose an Add-on."
                            state.timedOut ->
                                "The initial search has finished its time budget. " +
                                    "Choose an Add-on to search further or retry."
                            state.discoveryAttempted ->
                                "No verified chapter was found in the initial sources. " +
                                    "Choose another Add-on to search more."
                            else -> stringResource(MR.strings.tsuzuki_content_no_options)
                        },
                        onRetry = onRetry,
                        onOpenAddonsSettings = onOpenAddonsSettings,
                        onFindOrAddSource = sourceDiscoveryAction(state, onFindOrAddSource),
                    )
                }

                is ContentSelectorScreenState.Error -> {
                    SelectorUnavailableContent(
                        message = state.error.message
                            ?: stringResource(MR.strings.tsuzuki_content_selector_error),
                        onRetry = onRetry,
                        onOpenAddonsSettings = onOpenAddonsSettings,
                        onFindOrAddSource = sourceDiscoveryAction(state, onFindOrAddSource),
                    )
                }
            }
        }
    }
}

@Composable
private fun ContentOptionRow(
    item: ContentOptionPresentation,
    preferred: Boolean,
    active: Boolean,
    onClick: () -> Unit,
) {
    val supportingText = remember(item) {
        buildList {
            item.language?.takeIf(String::isNotBlank)?.let { add(it) }
            item.scanlationGroup?.takeIf(String::isNotBlank)?.let { add(it) }
            item.releaseDate?.let { releaseDate ->
                add(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(releaseDate)))
            }
        }.joinToString(" · ")
    }

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(
                text = item.addonDisplayName,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        supportingContent = supportingText.takeIf(String::isNotBlank)?.let { text ->
            { Text(text) }
        },
        trailingContent = if (preferred || active) {
            {
                Column {
                    if (active) Text("Reading now", style = MaterialTheme.typography.labelMedium)
                    if (preferred) {
                        Text(
                            stringResource(MR.strings.tsuzuki_content_preferred_badge),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun SelectorUnavailableContent(
    message: String,
    onRetry: () -> Unit,
    onOpenAddonsSettings: () -> Unit,
    onFindOrAddSource: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onRetry) {
                Text(stringResource(MR.strings.action_retry))
            }
            OutlinedButton(onClick = onOpenAddonsSettings) {
                Text(stringResource(MR.strings.tsuzuki_content_open_addons))
            }
        }
        onFindOrAddSource?.let { onClick ->
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onClick,
            ) {
                Text("Find or add reading source")
            }
        }
    }
}
