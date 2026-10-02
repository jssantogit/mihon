package eu.kanade.presentation.tsuzuki.collections

// Structural List Builder V2: Quick Builder + Advanced.

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionListDraft
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortDirection
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortKey
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortSelection

@Composable
internal fun ListBuilderScreen(
    title: String,
    initial: ListEditorState,
    onClose: () -> Unit,
    onConfirm: (CollectionListDraft) -> Unit,
) {
    var editor by remember(initial) { mutableStateOf(initial) }
    var advanced by remember(initial) { mutableStateOf(false) }
    val draft = editor.toDraftOrNull()

    BackHandler {
        if (advanced) {
            advanced = false
        } else {
            onClose()
        }
    }

    Scaffold(
        topBar = {
            AppBar(
                title = if (advanced) "Advanced Filters" else title,
                navigateUp = {
                    if (advanced) {
                        advanced = false
                    } else {
                        onClose()
                    }
                },
            )
        },
        bottomBar = {
            EditorBottomBar(
                label = if (advanced) {
                    "Apply filters"
                } else if (title == "New List") {
                    "Create List"
                } else {
                    "Save changes"
                },
                enabled = advanced || draft != null,
                onClick = {
                    if (advanced) {
                        advanced = false
                    } else {
                        draft?.let(onConfirm)
                    }
                },
            )
        },
    ) { padding ->
        if (advanced) {
            AdvancedListFilters(
                editor = editor,
                onEditorChange = { editor = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            QuickListBuilder(
                editor = editor,
                onEditorChange = { editor = it },
                onAdvanced = { advanced = true },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        }
    }
}

@Composable
private fun QuickListBuilder(
    editor: ListEditorState,
    onEditorChange: (ListEditorState) -> Unit,
    onAdvanced: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 12.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "basic") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorSectionLabel("BASIC")
                EditorSurface {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = editor.title,
                        onValueChange = { onEditorChange(editor.copy(title = it)) },
                        label = { Text("List name") },
                        singleLine = true,
                    )
                    BuilderDropdown(
                        label = "Display",
                        current = editor.layoutType ?: "Default",
                        options = listOf("Default", "Grid", "List"),
                        display = { it },
                        onSelect = { selected ->
                            onEditorChange(
                                editor.copy(
                                    layoutType = selected.lowercase().takeUnless { it == "default" },
                                ),
                            )
                        },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }

        item(key = "source") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorSectionLabel("SOURCE")
                EditorSurface {
                    val sources = remember(editor.providerId) {
                        (listOf(editor.providerId) + SUPPORTED_LIST_PROVIDER_IDS).distinct()
                    }
                    BuilderDropdown(
                        label = "Catalog source",
                        current = editor.providerId,
                        options = sources,
                        display = ::providerDisplayName,
                        onSelect = { onEditorChange(editor.copy(providerId = it)) },
                        enabled = editor.filtersEditable,
                    )
                    if (sources.size == 1) {
                        Text(
                            text = "Only Kitsu has an executable Collections adapter today. " +
                                "The source selector is provider-aware so additional adapters can be added without redesigning this screen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }

        if (!editor.filtersEditable) {
            item(key = "preserved_query_notice") {
                EditorSurface {
                    Text(
                        text = "Advanced imported query",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "This List contains a query the visual builder cannot represent safely. " +
                            "Its filters and source will be preserved exactly. You can still rename the List, change sort, or display.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        item(key = "quick_filters") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorSectionLabel("QUICK FILTERS")
                EditorSurface {
                    QuickChoiceRow(
                        label = "Status",
                        options = listOf(
                            QuickChoice("Any", null),
                            QuickChoice("Ongoing", "ONGOING"),
                            QuickChoice("Complete", "COMPLETED"),
                        ),
                        selected = editor.status,
                        enabled = editor.filtersEditable,
                        onSelect = { onEditorChange(editor.copy(status = it)) },
                    )
                    BuilderDropdown(
                        label = "Type",
                        current = editor.format ?: "Any",
                        options = listOf("Any") + providerFormatOptions(editor.providerId),
                        display = { it.prettyEnumName() },
                        onSelect = {
                            onEditorChange(editor.copy(format = it.takeUnless { value -> value == "Any" }))
                        },
                        enabled = editor.filtersEditable,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        value = editor.includeGenre,
                        onValueChange = {
                            if (editor.filtersEditable) {
                                onEditorChange(editor.copy(includeGenre = it))
                            }
                        },
                        enabled = editor.filtersEditable,
                        label = { Text("Genre contains") },
                        placeholder = { Text("e.g. Action") },
                        singleLine = true,
                    )
                    BuilderNumericField(
                        label = "Minimum rating",
                        value = editor.minScore,
                        enabled = editor.filtersEditable,
                        allowDecimal = true,
                        onValueChange = { onEditorChange(editor.copy(minScore = it)) },
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    BuilderDropdown(
                        label = "Sort",
                        current = editor.sort,
                        options = SUPPORTED_SORTS,
                        display = ::sortDisplayName,
                        onSelect = { onEditorChange(editor.copy(sort = it)) },
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }

        item(key = "active_filters") {
            ActiveFiltersCard(
                editor = editor,
                onEditorChange = onEditorChange,
            )
        }

        item(key = "advanced") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onAdvanced),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Advanced filters",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = if (editor.filtersEditable) {
                                "Genres, chapters, volumes, ratings, and provider-specific options."
                            } else {
                                "Inspect the preserved query state without rewriting it."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item(key = "preview") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorSectionLabel("PREVIEW")
                EditorSurface {
                    val activeCount = editor.activeFilterCount()
                    Text(
                        text = if (activeCount == 0) {
                            "No filters applied"
                        } else {
                            "$activeCount active filter${if (activeCount == 1) "" else "s"}"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "The structural preview surface is ready. Live preview of an unsaved query " +
                            "will be wired when draft execution is exposed by the Collections runtime; no fake result count is shown.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AdvancedListFilters(
    editor: ListEditorState,
    onEditorChange: (ListEditorState) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 12.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!editor.filtersEditable) {
            item(key = "locked") {
                EditorSurface {
                    Text(
                        text = "Filters preserved exactly",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "This imported query uses boolean or provider-specific semantics " +
                            "outside the current visual builder. Tsuzuki will keep the original AST unchanged " +
                            "rather than silently simplifying it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    editor.preservedQuery?.let {
                        Text(
                            text = it.toCanonicalString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        } else {
            item(key = "publication") {
                AdvancedSection("PUBLICATION") {
                    BuilderNumericField(
                        label = "Minimum chapters",
                        value = editor.minChapters,
                        onValueChange = { onEditorChange(editor.copy(minChapters = it)) },
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    BuilderNumericField(
                        label = "Minimum volumes",
                        value = editor.minVolumes,
                        onValueChange = { onEditorChange(editor.copy(minVolumes = it)) },
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }

            item(key = "genres_tags") {
                AdvancedSection(
                    if (editor.providerId.equals("kitsu", ignoreCase = true)) "GENRES" else "GENRES & TAGS",
                ) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = editor.includeGenre,
                        onValueChange = { onEditorChange(editor.copy(includeGenre = it)) },
                        label = { Text("Include genre") },
                        placeholder = { Text("e.g. Action") },
                        singleLine = true,
                    )
                    if (!editor.providerId.equals("kitsu", ignoreCase = true)) {
                        OutlinedTextField(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            value = editor.excludeGenre,
                            onValueChange = { onEditorChange(editor.copy(excludeGenre = it)) },
                            label = { Text("Exclude genre") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            value = editor.includeTag,
                            onValueChange = { onEditorChange(editor.copy(includeTag = it)) },
                            label = { Text("Include tag") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            value = editor.excludeTag,
                            onValueChange = { onEditorChange(editor.copy(excludeTag = it)) },
                            label = { Text("Exclude tag") },
                            singleLine = true,
                        )
                    } else {
                        Text(
                            text = "Kitsu supports positive genre filtering. Exclusions and tags stay hidden until " +
                                "their provider semantics are executable without scanning missing metadata.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            item(key = "ratings") {
                AdvancedSection("RATINGS") {
                    BuilderNumericField(
                        label = "Minimum rating",
                        value = editor.minScore,
                        allowDecimal = true,
                        onValueChange = { onEditorChange(editor.copy(minScore = it)) },
                    )
                }
            }

            item(key = "provider_options") {
                AdvancedSection("PROVIDER OPTIONS") {
                    Text(
                        text = "Source: ${providerDisplayName(editor.providerId)}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "Only filters that the current Tsuzuki planner can execute safely are exposed here. " +
                            "Provider-specific controls can be added behind this section as capability descriptors grow.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AdvancedSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EditorSectionLabel(title)
        EditorSurface(content = content)
    }
}

@Composable
private fun QuickChoiceRow(
    label: String,
    options: List<QuickChoice>,
    selected: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            items(options, key = { it.label }) { option ->
                FilterChip(
                    selected = option.value == selected,
                    onClick = { if (enabled) onSelect(option.value) },
                    enabled = enabled,
                    label = { Text(option.label) },
                )
            }
        }
    }
}

@Composable
private fun ActiveFiltersCard(
    editor: ListEditorState,
    onEditorChange: (ListEditorState) -> Unit,
) {
    val filters = buildList {
        editor.status?.let {
            add(ActiveFilter(it.prettyEnumName()) { onEditorChange(editor.copy(status = null)) })
        }
        editor.format?.let {
            add(ActiveFilter(it.prettyEnumName()) { onEditorChange(editor.copy(format = null)) })
        }
        editor.includeGenre.takeIf(String::isNotBlank)?.let {
            add(ActiveFilter("Genre: $it") { onEditorChange(editor.copy(includeGenre = "")) })
        }
        editor.excludeGenre.takeIf(String::isNotBlank)?.let {
            add(ActiveFilter("Exclude genre: $it") { onEditorChange(editor.copy(excludeGenre = "")) })
        }
        editor.includeTag.takeIf(String::isNotBlank)?.let {
            add(ActiveFilter("Tag: $it") { onEditorChange(editor.copy(includeTag = "")) })
        }
        editor.excludeTag.takeIf(String::isNotBlank)?.let {
            add(ActiveFilter("Exclude tag: $it") { onEditorChange(editor.copy(excludeTag = "")) })
        }
        editor.minScore.takeIf(String::isNotBlank)?.let {
            add(ActiveFilter("Rating ≥ $it") { onEditorChange(editor.copy(minScore = "")) })
        }
        editor.minChapters.takeIf(String::isNotBlank)?.let {
            add(ActiveFilter("Chapters ≥ $it") { onEditorChange(editor.copy(minChapters = "")) })
        }
        editor.minVolumes.takeIf(String::isNotBlank)?.let {
            add(ActiveFilter("Volumes ≥ $it") { onEditorChange(editor.copy(minVolumes = "")) })
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditorSectionLabel("ACTIVE FILTERS")
            Text(
                text = filters.size.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
            if (filters.isNotEmpty() && editor.filtersEditable) {
                TextButton(
                    onClick = {
                        onEditorChange(
                            editor.copy(
                                status = null,
                                format = null,
                                includeGenre = "",
                                excludeGenre = "",
                                includeTag = "",
                                excludeTag = "",
                                minScore = "",
                                minChapters = "",
                                minVolumes = "",
                            ),
                        )
                    },
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text("Clear all")
                }
            }
        }
        EditorSurface {
            if (filters.isEmpty()) {
                Text(
                    text = if (editor.filtersEditable) "No active filters" else "Imported query preserved",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filters, key = ActiveFilter::label) { filter ->
                        FilterChip(
                            selected = true,
                            enabled = editor.filtersEditable,
                            onClick = filter.onClear,
                            label = { Text(filter.label) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> BuilderDropdown(
    label: String,
    current: T,
    options: List<T>,
    display: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
        )
        TextButton(
            onClick = { if (enabled) expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = display(current),
                    modifier = Modifier.weight(1f),
                )
                Text("⌄")
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(display(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun BuilderNumericField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    allowDecimal: Boolean = false,
) {
    OutlinedTextField(
        modifier = modifier.fillMaxWidth(),
        value = value,
        enabled = enabled,
        onValueChange = { candidate ->
            val valid = if (allowDecimal) {
                candidate.isEmpty() || candidate.toDoubleOrNull() != null
            } else {
                candidate.isEmpty() || candidate.toLongOrNull() != null
            }
            if (valid) onValueChange(candidate)
        },
        label = { Text(label) },
        singleLine = true,
    )
}

private data class QuickChoice(
    val label: String,
    val value: String?,
)

private data class ActiveFilter(
    val label: String,
    val onClear: () -> Unit,
)

private fun ListEditorState.activeFilterCount(): Int = listOf(
    status,
    format,
    includeGenre.takeIf(String::isNotBlank),
    excludeGenre.takeIf(String::isNotBlank),
    includeTag.takeIf(String::isNotBlank),
    excludeTag.takeIf(String::isNotBlank),
    minScore.takeIf(String::isNotBlank),
    minChapters.takeIf(String::isNotBlank),
    minVolumes.takeIf(String::isNotBlank),
).count { it != null }

private fun providerFormatOptions(providerId: String): List<String> = when (providerId.lowercase()) {
    "kitsu" -> listOf(
        CatalogItemFormat.MANGA,
        CatalogItemFormat.NOVEL,
        CatalogItemFormat.ONE_SHOT,
        CatalogItemFormat.MANHWA,
        CatalogItemFormat.MANHUA,
        CatalogItemFormat.DOUJIN,
    ).map(CatalogItemFormat::name)

    else ->
        CatalogItemFormat.entries
            .filterNot { it == CatalogItemFormat.UNKNOWN }
            .map(CatalogItemFormat::name)
}

private fun providerDisplayName(providerId: String): String = when (providerId.lowercase()) {
    "kitsu" -> "Kitsu"
    "myanimelist", "mal" -> "MyAnimeList"
    "mangaupdates" -> "MangaUpdates"
    "bangumi" -> "Bangumi"
    "komga" -> "Komga"
    "kavita" -> "Kavita"
    "suwayomi" -> "Suwayomi"
    else -> providerId
}

private fun sortDisplayName(sort: CollectionSortSelection): String = when (sort.key) {
    CollectionSortKey.Standard.POPULARITY -> when (sort.direction) {
        CollectionSortDirection.DESC -> "Popularity (High to Low)"
        CollectionSortDirection.ASC -> "Popularity (Low to High)"
        null -> "Popularity"
    }
    CollectionSortKey.Standard.RATING -> when (sort.direction) {
        CollectionSortDirection.DESC -> "Rating (High to Low)"
        CollectionSortDirection.ASC -> "Rating (Low to High)"
        null -> "Rating"
    }
    CollectionSortKey.Standard.UPDATED -> when (sort.direction) {
        CollectionSortDirection.DESC -> "Recently Updated"
        CollectionSortDirection.ASC -> "Oldest Updated"
        null -> "Updated"
    }
    CollectionSortKey.Standard.RELEVANCE -> "Relevance"
    is CollectionSortKey.Provider -> sort.key.nativeId.prettyEnumName()
}

private fun String.prettyEnumName(): String = lowercase()
    .replace('_', ' ')
    .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

private val SUPPORTED_LIST_PROVIDER_IDS = listOf("kitsu")
