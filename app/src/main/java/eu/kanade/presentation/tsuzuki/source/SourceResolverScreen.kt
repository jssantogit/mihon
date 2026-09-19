package eu.kanade.presentation.tsuzuki.source

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.tachiyomi.ui.tsuzuki.source.SourceResolverScreenState
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Settings
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.source.model.ScoredSourceCandidate
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun SourceResolverScreen(
    state: SourceResolverScreenState,
    navigateUp: () -> Unit,
    onSelectLanguage: (String) -> Unit,
    onConfirm: (ScoredSourceCandidate) -> Unit,
    onCheckMoreSources: () -> Unit,
    onSetTitleSourceOverride: (String?) -> Unit,
    onRetry: () -> Unit,
    onOpenPreferences: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { scrollBehavior ->
            AppBar(
                titleContent = { AppBarTitle(state.titleOrNull() ?: "Reading source") },
                navigateUp = navigateUp,
                actions = {
                    IconButton(onClick = onOpenPreferences) {
                        Icon(MaterialSymbols.Rounded.Settings, contentDescription = "Source preferences")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            when (state) {
                SourceResolverScreenState.Loading -> LoadingScreen()
                is SourceResolverScreenState.Searching -> SearchingContent(state)
                is SourceResolverScreenState.SelectLanguage -> LanguageSelectionContent(
                    state = state,
                    onSelectLanguage = onSelectLanguage,
                )
                is SourceResolverScreenState.NoPreferredSources -> NoPreferredSourcesContent(
                    state = state,
                    onOpenPreferences = onOpenPreferences,
                )
                is SourceResolverScreenState.Resolved -> ResolvedContent(
                    state = state,
                    onSetTitleSourceOverride = onSetTitleSourceOverride,
                )
                is SourceResolverScreenState.NeedsConfirmation -> ConfirmationContent(
                    state = state,
                    onConfirm = onConfirm,
                )
                is SourceResolverScreenState.NotFound -> NotFoundContent(
                    state = state,
                    onCheckMoreSources = onCheckMoreSources,
                    onOpenPreferences = onOpenPreferences,
                )
                is SourceResolverScreenState.Conflict -> EmptyScreen(
                    message = "This source is already mapped to another canonical title " +
                        "(${state.existingCanonicalTitleId}).",
                )
                is SourceResolverScreenState.Error -> MessageWithAction(
                    message = state.error.message ?: "Unable to resolve a reading source",
                    actionLabel = "Retry",
                    onAction = onRetry,
                )
            }
        }
    }
}

private fun SourceResolverScreenState.titleOrNull(): String? = when (this) {
    SourceResolverScreenState.Loading -> null
    is SourceResolverScreenState.SelectLanguage -> title
    is SourceResolverScreenState.Searching -> title
    is SourceResolverScreenState.Resolved -> title
    is SourceResolverScreenState.NeedsConfirmation -> title
    is SourceResolverScreenState.NoPreferredSources -> title
    is SourceResolverScreenState.NotFound -> title
    is SourceResolverScreenState.Conflict -> title
    is SourceResolverScreenState.Error -> title
}

@Composable
private fun SearchingContent(state: SourceResolverScreenState.Searching) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LoadingScreen()
        Text(
            text = if (state.broaden) {
                "Checking more installed sources (${state.language})…"
            } else {
                "Searching preferred sources (${state.language})…"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LanguageSelectionContent(
    state: SourceResolverScreenState.SelectLanguage,
    onSelectLanguage: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Choose a reading language for ${state.title}.", style = MaterialTheme.typography.titleMedium)
        state.languages.forEach { language ->
            Button(
                onClick = { onSelectLanguage(language) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(language)
            }
        }
    }
}

@Composable
private fun NoPreferredSourcesContent(
    state: SourceResolverScreenState.NoPreferredSources,
    onOpenPreferences: () -> Unit,
) {
    MessageWithAction(
        message = if (state.language.isBlank()) {
            "Configure reading sources before resolving ${state.title}."
        } else {
            "No preferred sources configured for ${state.language}."
        },
        actionLabel = "Open source preferences",
        onAction = onOpenPreferences,
    )
}

@Composable
private fun ResolvedContent(
    state: SourceResolverScreenState.Resolved,
    onSetTitleSourceOverride: (String?) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "summary") {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Reading source", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Source #${state.mapping.sourceId}")
                    Text("Language: ${state.mapping.language}")
                    if (state.reused) {
                        Text("Persisted mapping reused", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (state.mappings.size > 1) {
            item(key = "mapping_header") {
                Text("Title-level preferred source", style = MaterialTheme.typography.titleMedium)
            }
            items(state.mappings, key = { it.id }) { mapping ->
                MappingRow(
                    mapping = mapping,
                    onSelect = { onSetTitleSourceOverride(mapping.id) },
                )
            }
        }
    }
}

@Composable
private fun MappingRow(
    mapping: SourceTitleMapping,
    onSelect: () -> Unit,
) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Source #${mapping.sourceId}")
                Text(mapping.language, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onSelect, enabled = !mapping.preferredOverride) {
                Text(if (mapping.preferredOverride) "Preferred" else "Use for title")
            }
        }
    }
}

@Composable
private fun ConfirmationContent(
    state: SourceResolverScreenState.NeedsConfirmation,
    onConfirm: (ScoredSourceCandidate) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Text(
                text = "Choose a reading source for ${state.title}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Candidates are not confirmed until you choose one.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(state.candidates, key = { "${it.candidate.sourceId}:${it.candidate.sourceUrl}" }) { scored ->
            CandidateRow(scored = scored, onConfirm = { onConfirm(scored) })
        }
    }
}

@Composable
private fun CandidateRow(
    scored: ScoredSourceCandidate,
    onConfirm: () -> Unit,
) {
    val candidate = scored.candidate
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            candidate.thumbnailUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = "Thumbnail for ${candidate.title}",
                    modifier = Modifier
                        .height(72.dp)
                        .fillMaxWidth(0.22f),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(candidate.title, style = MaterialTheme.typography.titleSmall)
                Text(candidate.sourceName)
                Text(candidate.language)
                Text(
                    "Confidence: ${(scored.confidence * 100).toInt()}%",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onConfirm) { Text("Choose this source") }
            }
        }
    }
}

@Composable
private fun NotFoundContent(
    state: SourceResolverScreenState.NotFound,
    onCheckMoreSources: () -> Unit,
    onOpenPreferences: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "No reading source found for ${state.title} in ${state.language.ifBlank {
                "the selected language"
            }}.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.canBroaden) {
            Button(onClick = onCheckMoreSources) { Text("Check more sources") }
        }
        TextButton(onClick = onOpenPreferences) { Text("Open source preferences") }
    }
}

@Composable
private fun MessageWithAction(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onAction) { Text(actionLabel) }
    }
}
