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

@Composable
fun ContentOptionSelectorSheet(
    state: ContentSelectorScreenState,
    onSelect: (ContentOptionPresentation) -> Unit,
    onRetry: () -> Unit,
    onOpenAddonsSettings: () -> Unit,
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

            when (state) {
                ContentSelectorScreenState.Loading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is ContentSelectorScreenState.Ready -> {
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
                                preferred = state.preferredAddonId == item.option.addonId,
                                onClick = { onSelect(item) },
                            )
                        }
                    }
                }

                is ContentSelectorScreenState.Empty -> {
                    SelectorUnavailableContent(
                        message = stringResource(MR.strings.tsuzuki_content_no_options),
                        onRetry = onRetry,
                        onOpenAddonsSettings = onOpenAddonsSettings,
                    )
                }

                is ContentSelectorScreenState.Error -> {
                    SelectorUnavailableContent(
                        message = state.error.message
                            ?: stringResource(MR.strings.tsuzuki_content_selector_error),
                        onRetry = onRetry,
                        onOpenAddonsSettings = onOpenAddonsSettings,
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
        trailingContent = if (preferred) {
            { Text(stringResource(MR.strings.tsuzuki_content_preferred_badge)) }
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
    }
}
