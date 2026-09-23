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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.tachiyomi.ui.tsuzuki.source.ReadingSourcePreferencesScreenState
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.ArrowDownward
import mihon.icons.materialsymbols.rounded.ArrowUpward
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Save
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceDescriptor
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun ReadingSourcePreferencesScreen(
    state: ReadingSourcePreferencesScreenState,
    navigateUp: () -> Unit,
    onSelectLanguage: (String) -> Unit,
    onAddSource: (Long) -> Unit,
    onRemoveSource: (Long) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { scrollBehavior ->
            AppBar(
                titleContent = { AppBarTitle("Reading sources") },
                navigateUp = navigateUp,
                actions = {
                    val success = state as? ReadingSourcePreferencesScreenState.Success
                    IconButton(
                        onClick = onSave,
                        enabled = success?.hasPendingChanges == true && success?.isSaving != true,
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.Save,
                            contentDescription = "Save",
                        )
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
                ReadingSourcePreferencesScreenState.Loading -> LoadingScreen()
                is ReadingSourcePreferencesScreenState.Error -> EmptyScreen(
                    message = state.error.message ?: "Unable to load reading sources",
                )
                is ReadingSourcePreferencesScreenState.Success -> PreferencesContent(
                    state = state,
                    onSelectLanguage = onSelectLanguage,
                    onAddSource = onAddSource,
                    onRemoveSource = onRemoveSource,
                    onMoveUp = onMoveUp,
                    onMoveDown = onMoveDown,
                    onSave = onSave,
                )
            }
        }
    }
}

@Composable
private fun PreferencesContent(
    state: ReadingSourcePreferencesScreenState.Success,
    onSelectLanguage: (String) -> Unit,
    onAddSource: (Long) -> Unit,
    onRemoveSource: (Long) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var languageMenuExpanded by remember { mutableStateOf(false) }
    var languageInput by remember(state.selectedLanguage) { mutableStateOf(state.selectedLanguage) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "language") {
            Text(
                text = "Language",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box {
                TextButton(onClick = { languageMenuExpanded = true }) {
                    Text(state.selectedLanguage.ifBlank { "Choose a language" })
                }
                DropdownMenu(
                    expanded = languageMenuExpanded,
                    onDismissRequest = { languageMenuExpanded = false },
                ) {
                    state.languages.forEach { language ->
                        DropdownMenuItem(
                            text = { Text(language) },
                            onClick = {
                                languageMenuExpanded = false
                                onSelectLanguage(language)
                            },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = languageInput,
                    onValueChange = { languageInput = it },
                    label = { Text("Language code") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { onSelectLanguage(languageInput) },
                    enabled = languageInput.isNotBlank(),
                ) {
                    Text("Use")
                }
            }
        }

        item(key = "configured_header") {
            Text(
                text = "Configured source order",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        if (state.configuredSources.isEmpty()) {
            item(key = "configured_empty") {
                Text(
                    text = "No sources configured for this language.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(
                items = state.configuredSources,
                key = { it.sourceId },
            ) { source ->
                val index = state.configuredSources.indexOf(source)
                ConfiguredSourceRow(
                    source = source,
                    canMoveUp = index > 0,
                    canMoveDown = index < state.configuredSources.lastIndex,
                    onRemove = { onRemoveSource(source.sourceId) },
                    onMoveUp = { onMoveUp(index) },
                    onMoveDown = { onMoveDown(index) },
                )
            }
        }

        item(key = "available_header") {
            Text(
                text = "Installed sources",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        if (state.availableSources.isEmpty()) {
            item(key = "available_empty") {
                Text(
                    text = "No additional eligible installed sources.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(
                items = state.availableSources,
                key = { "available:${it.sourceId}" },
            ) { source ->
                AvailableSourceRow(source = source, onAdd = { onAddSource(source.sourceId) })
            }
        }

        item(key = "save") {
            Button(
                onClick = onSave,
                enabled = state.hasPendingChanges && !state.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.isSaving) "Saving…" else "Save")
            }
        }
    }
}

@Composable
private fun ConfiguredSourceRow(
    source: ReadingSourceDescriptor,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(source.name, modifier = Modifier.weight(1f))
            IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                Icon(MaterialSymbols.Rounded.ArrowUpward, contentDescription = "Move Up")
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                Icon(MaterialSymbols.Rounded.ArrowDownward, contentDescription = "Move Down")
            }
            IconButton(onClick = onRemove) {
                Icon(
                    MaterialSymbols.Rounded.Delete,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AvailableSourceRow(
    source: ReadingSourceDescriptor,
    onAdd: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(source.name, modifier = Modifier.weight(1f))
            TextButton(onClick = onAdd) { Text("Add") }
        }
    }
}
