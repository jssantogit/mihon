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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionDraftPreviewState
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionFilterLookupKey
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionFilterLookupState
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionListDraft
import tachiyomi.domain.tsuzuki.collections.capability.CollectionFilterCapability
import tachiyomi.domain.tsuzuki.collections.capability.CollectionProviderDescriptor
import tachiyomi.domain.tsuzuki.collections.capability.CollectionProviderSwitchPlan
import tachiyomi.domain.tsuzuki.collections.capability.CollectionProviderSwitchPlanner
import tachiyomi.domain.tsuzuki.collections.capability.ProviderQueryCapabilities
import tachiyomi.domain.tsuzuki.collections.capability.FilterPlacement
import tachiyomi.domain.tsuzuki.collections.capability.FilterValueSource
import tachiyomi.domain.tsuzuki.collections.capability.MultiValueMode
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortDirection
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortKey
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortSelection
import tachiyomi.domain.tsuzuki.collections.model.SortDirectionMode
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

@Composable
internal fun ListBuilderScreen(
    title: String,
    initial: ListEditorState,
    providerDescriptors: List<CollectionProviderDescriptor> = emptyList(),
    providerCapabilities: Map<String, ProviderQueryCapabilities> = emptyMap(),
    previewState: CollectionDraftPreviewState = CollectionDraftPreviewState.Idle,
    filterLookupStates: Map<CollectionFilterLookupKey, CollectionFilterLookupState> = emptyMap(),
    onPreviewDraft: (CollectionListDraft?) -> Unit = {},
    onFilterLookup: (String, String, String) -> Unit = { _, _, _ -> },
    onClose: () -> Unit,
    onConfirm: (CollectionListDraft) -> Unit,
) {
    var editor by remember(initial) { mutableStateOf(initial) }
    var advanced by remember(initial) { mutableStateOf(false) }
    var pendingSwitch by remember(initial) { mutableStateOf<CollectionProviderSwitchPlan.Ready?>(null) }
    var blockedSwitchMessage by remember(initial) { mutableStateOf<String?>(null) }
    val draft = editor.toDraftOrNull()

    LaunchedEffect(draft) {
        onPreviewDraft(draft)
    }
    DisposableEffect(Unit) {
        onDispose {
            onPreviewDraft(null)
        }
    }

    BackHandler {
        if (advanced) {
            advanced = false
        } else {
            onClose()
        }
    }

    fun applyProviderSwitch(plan: CollectionProviderSwitchPlan.Ready) {
        val target = providerCapabilities[plan.targetProviderId] ?: return
        val targetSort = plan.sort
            ?: target.descriptor.uiSortSelections().firstOrNull()
            ?: return
        editor = ListEditorState.fromDraft(
            CollectionListDraft(
                title = editor.title,
                query = plan.query,
                sort = targetSort,
                layoutType = editor.layoutType,
                providerId = plan.targetProviderId,
            ),
        )
    }

    fun requestProviderSwitch(targetProviderId: String) {
        if (targetProviderId == editor.providerId) return
        val target = providerCapabilities[targetProviderId] ?: return
        when (
            val plan = CollectionProviderSwitchPlanner.plan(
                sourceProviderId = editor.providerId,
                target = target,
                query = editor.currentQuery(),
                sort = editor.sort,
            )
        ) {
            is CollectionProviderSwitchPlan.Blocked -> {
                blockedSwitchMessage =
                    "This imported query is too complex to translate safely. Reset its filters before changing source."
            }
            is CollectionProviderSwitchPlan.Ready -> {
                if (plan.requiresConfirmation) {
                    pendingSwitch = plan
                } else {
                    applyProviderSwitch(plan)
                }
            }
        }
    }

    pendingSwitch?.let { plan ->
        AlertDialog(
            onDismissRequest = { pendingSwitch = null },
            title = { Text("Change catalog source?") },
            text = {
                val removed = buildList {
                    addAll(plan.removedExpressions.map(QueryExpression::toCanonicalString))
                    plan.removedSort?.let { add("Sort: ${it.cacheKey}") }
                }
                Text(
                    "The target provider cannot execute these filters/sort with the same semantics: " +
                        removed.joinToString() +
                        ". They will be removed only if you confirm.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingSwitch = null
                        applyProviderSwitch(plan)
                    },
                ) {
                    Text("Change source")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSwitch = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    blockedSwitchMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { blockedSwitchMessage = null },
            title = { Text("Source change blocked") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { blockedSwitchMessage = null }) {
                    Text("OK")
                }
            },
        )
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
                descriptor = providerDescriptors.firstOrNull { it.providerId == editor.providerId },
                filterLookupStates = filterLookupStates,
                onFilterLookup = onFilterLookup,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            QuickListBuilder(
                editor = editor,
                onEditorChange = { editor = it },
                onAdvanced = { advanced = true },
                providerDescriptors = providerDescriptors,
                onProviderSelect = ::requestProviderSwitch,
                previewState = previewState,
                filterLookupStates = filterLookupStates,
                onFilterLookup = onFilterLookup,
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
    providerDescriptors: List<CollectionProviderDescriptor>,
    onProviderSelect: (String) -> Unit,
    previewState: CollectionDraftPreviewState,
    filterLookupStates: Map<CollectionFilterLookupKey, CollectionFilterLookupState>,
    onFilterLookup: (String, String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val descriptor = providerDescriptors.firstOrNull { it.providerId == editor.providerId }
    val quickFilters = descriptor?.filters?.filter { it.placement == FilterPlacement.QUICK }.orEmpty()
    val sortOptions = descriptor?.uiSortSelections().orEmpty()

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
                    val sources = listBuilderProviderIds(
                        descriptors = providerDescriptors,
                        currentProviderId = editor.providerId,
                    )
                    BuilderDropdown(
                        label = "Catalog source",
                        current = editor.providerId,
                        options = sources,
                        display = { providerId ->
                            providerDescriptors
                                .firstOrNull { it.providerId == providerId }
                                ?.displayName
                                ?: providerDisplayName(providerId)
                        },
                        onSelect = onProviderSelect,
                        enabled = editor.filtersEditable && sources.isNotEmpty(),
                    )
                    if (descriptor == null) {
                        Text(
                            text = "This provider is not currently registered for Collections execution. " +
                                "The saved definition is preserved, but Preview cannot execute it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
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
                    quickFilters.forEach { capability ->
                        DescriptorFilterControl(
                            capability = capability,
                            editor = editor,
                            onEditorChange = onEditorChange,
                            filterLookupStates = filterLookupStates,
                            onFilterLookup = onFilterLookup,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }

                    if (sortOptions.isNotEmpty()) {
                        val selectedSort = editor.sort.takeIf { it in sortOptions } ?: sortOptions.first()
                        BuilderDropdown(
                            label = "Sort",
                            current = selectedSort,
                            options = sortOptions,
                            display = { sort -> descriptor?.sortDisplayName(sort) ?: sortDisplayName(sort) },
                            onSelect = { onEditorChange(editor.copy(sort = it)) },
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }

                    if (quickFilters.isEmpty() && sortOptions.isEmpty()) {
                        Text(
                            text = "This provider does not expose Quick filters.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                    when (previewState) {
                        CollectionDraftPreviewState.Idle -> {
                            Text(
                                text = "Preview becomes available when the current draft is executable.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        CollectionDraftPreviewState.Loading -> {
                            Text(
                                text = "Loading preview…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        is CollectionDraftPreviewState.Content -> {
                            if (previewState.items.isEmpty()) {
                                Text(
                                    text = "No matching titles in this bounded preview window.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            } else {
                                previewState.items.take(PREVIEW_TITLE_LIMIT).forEach { item ->
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }
                            if (previewState.scanBudgetReason != null) {
                                Text(
                                    text = "Preview scan limit reached; more remote candidates remain.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                        is CollectionDraftPreviewState.Error -> {
                            Text(
                                text = previewState.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdvancedListFilters(
    editor: ListEditorState,
    onEditorChange: (ListEditorState) -> Unit,
    descriptor: CollectionProviderDescriptor?,
    filterLookupStates: Map<CollectionFilterLookupKey, CollectionFilterLookupState>,
    onFilterLookup: (String, String, String) -> Unit,
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
            val advancedCapabilities = descriptor?.filters
                ?.filter { it.placement == FilterPlacement.ADVANCED }
                .orEmpty()

            if (advancedCapabilities.isNotEmpty()) {
                item(key = "provider_options") {
                    AdvancedSection("FILTERS") {
                        advancedCapabilities.forEachIndexed { index, capability ->
                            DescriptorFilterControl(
                                capability = capability,
                                editor = editor,
                                onEditorChange = onEditorChange,
                                filterLookupStates = filterLookupStates,
                                onFilterLookup = onFilterLookup,
                                modifier = if (index == 0) Modifier else Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                }
            }

            if (advancedCapabilities.isEmpty()) {
                item(key = "no_advanced") {
                    EditorSurface {
                        Text(
                            text = "This provider has no additional verified Advanced filters.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
        editor.extraTerms.forEach { expression ->
            add(
                ActiveFilter(expression.toCanonicalString()) {
                    onEditorChange(editor.copy(extraTerms = editor.extraTerms - expression))
                },
            )
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
                                extraTerms = emptyList(),
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
private fun DescriptorFilterControl(
    capability: CollectionFilterCapability,
    editor: ListEditorState,
    onEditorChange: (ListEditorState) -> Unit,
    filterLookupStates: Map<CollectionFilterLookupKey, CollectionFilterLookupState>,
    onFilterLookup: (String, String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val positivePredicates = editor.simplePredicates(capability.field, negated = false)
    val negativePredicates = editor.simplePredicates(capability.field, negated = true)
    val positivePredicate = positivePredicates.firstOrNull()
    val negativePredicate = negativePredicates.firstOrNull()

    when (val source = capability.valueSource) {
        is FilterValueSource.Static -> {
            val options = source.values
                .mapNotNull { option ->
                    val value = option.value as? QueryValue.StringValue ?: return@mapNotNull null
                    option.label to value.value
                }
            if (options.isNotEmpty()) {
                if (capability.multiValueMode == MultiValueMode.INCLUDE_EXCLUDE) {
                    Column(modifier = modifier) {
                        DescriptorStaticDropdown(
                            label = "Include ${capability.id.prettyEnumName()}",
                            options = options,
                            predicate = positivePredicate,
                            editor = editor,
                            capability = capability,
                            negated = false,
                            onEditorChange = onEditorChange,
                        )
                        DescriptorStaticDropdown(
                            label = "Exclude ${capability.id.prettyEnumName()}",
                            options = options,
                            predicate = negativePredicate,
                            editor = editor,
                            capability = capability,
                            negated = true,
                            onEditorChange = onEditorChange,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                } else {
                    DescriptorStaticDropdown(
                        label = capability.id.prettyEnumName(),
                        options = options,
                        predicate = positivePredicate,
                        editor = editor,
                        capability = capability,
                        negated = false,
                        onEditorChange = onEditorChange,
                        modifier = modifier,
                    )
                }
            }
        }

        FilterValueSource.BooleanToggle -> {
            val selected = (positivePredicate?.value as? QueryValue.BooleanValue)?.value == true
            FilterChip(
                selected = selected,
                onClick = {
                    onEditorChange(
                        editor.replaceFieldTerm(
                            field = capability.field,
                            negated = false,
                            predicate = if (selected) {
                                null
                            } else {
                                QueryExpression.Predicate(
                                    field = capability.field,
                                    operator = capability.preferredScalarOperator(),
                                    value = QueryValue.of(true),
                                )
                            },
                        ),
                    )
                },
                enabled = editor.filtersEditable,
                label = { Text(capability.id.prettyEnumName()) },
                modifier = modifier,
            )
        }

        FilterValueSource.IntegerRange,
        FilterValueSource.DecimalRange,
        -> {
            val range = positivePredicate.toNumericBounds()
            Column(modifier = modifier) {
                Text(capability.id.prettyEnumName(), style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BuilderNumericField(
                        label = "Min",
                        value = range.first,
                        enabled = editor.filtersEditable,
                        allowDecimal = source == FilterValueSource.DecimalRange,
                        onValueChange = { value ->
                            onEditorChange(
                                editor.replaceFieldTerm(
                                    field = capability.field,
                                    negated = false,
                                    predicate = capability.rangeExpression(
                                        minimum = value,
                                        maximum = range.second,
                                        decimal = source == FilterValueSource.DecimalRange,
                                    ),
                                ),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                    BuilderNumericField(
                        label = "Max",
                        value = range.second,
                        enabled = editor.filtersEditable,
                        allowDecimal = source == FilterValueSource.DecimalRange,
                        onValueChange = { value ->
                            onEditorChange(
                                editor.replaceFieldTerm(
                                    field = capability.field,
                                    negated = false,
                                    predicate = capability.rangeExpression(
                                        minimum = range.first,
                                        maximum = value,
                                        decimal = source == FilterValueSource.DecimalRange,
                                    ),
                                ),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        FilterValueSource.DateRange -> {
            val range = positivePredicate.toStringBounds()
            Column(modifier = modifier) {
                Text(capability.id.prettyEnumName(), style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = range.first,
                    onValueChange = { value ->
                        onEditorChange(
                            editor.replaceFieldTerm(
                                field = capability.field,
                                negated = false,
                                predicate = capability.stringRangeExpression(value, range.second),
                            ),
                        )
                    },
                    enabled = editor.filtersEditable,
                    label = { Text("From (YYYY-MM-DD)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    value = range.second,
                    onValueChange = { value ->
                        onEditorChange(
                            editor.replaceFieldTerm(
                                field = capability.field,
                                negated = false,
                                predicate = capability.stringRangeExpression(range.first, value),
                            ),
                        )
                    },
                    enabled = editor.filtersEditable,
                    label = { Text("To (YYYY-MM-DD)") },
                    singleLine = true,
                )
            }
        }

        FilterValueSource.FreeText -> {
            if (capability.multiValueMode == MultiValueMode.INCLUDE_EXCLUDE) {
                Column(modifier = modifier) {
                    DescriptorTextField(
                        label = "Include ${capability.id.prettyEnumName()}",
                        value = (positivePredicate?.value as? QueryValue.StringValue)?.value.orEmpty(),
                        editor = editor,
                        capability = capability,
                        negated = false,
                        onEditorChange = onEditorChange,
                    )
                    DescriptorTextField(
                        label = "Exclude ${capability.id.prettyEnumName()}",
                        value = (negativePredicate?.value as? QueryValue.StringValue)?.value.orEmpty(),
                        editor = editor,
                        capability = capability,
                        negated = true,
                        onEditorChange = onEditorChange,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                DescriptorTextField(
                    label = capability.id.prettyEnumName(),
                    value = (positivePredicate?.value as? QueryValue.StringValue)?.value.orEmpty(),
                    editor = editor,
                    capability = capability,
                    negated = false,
                    onEditorChange = onEditorChange,
                    modifier = modifier,
                )
            }
        }

        is FilterValueSource.RemoteLookup -> {
            RemoteLookupFilterControl(
                providerId = editor.providerId,
                capability = capability,
                source = source,
                positivePredicates = positivePredicates,
                negativePredicates = negativePredicates,
                editor = editor,
                onEditorChange = onEditorChange,
                lookupState = filterLookupStates[
                    CollectionFilterLookupKey(editor.providerId, source.lookupId)
                ],
                onFilterLookup = onFilterLookup,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun DescriptorStaticDropdown(
    label: String,
    options: List<Pair<String, String>>,
    predicate: QueryExpression.Predicate?,
    editor: ListEditorState,
    capability: CollectionFilterCapability,
    negated: Boolean,
    onEditorChange: (ListEditorState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = (predicate?.value as? QueryValue.StringValue)?.value ?: "Any"
    BuilderDropdown(
        label = label,
        current = current,
        options = listOf("Any") + options.map { it.second },
        display = { value ->
            if (value == "Any") value else options.firstOrNull { it.second == value }?.first ?: value
        },
        onSelect = { selected ->
            val predicateExpression = selected.takeUnless { it == "Any" }?.let { value ->
                QueryExpression.Predicate(
                    field = capability.field,
                    operator = capability.preferredScalarOperator(),
                    value = QueryValue.of(value),
                )
            }
            onEditorChange(
                editor.replaceFieldTerm(
                    field = capability.field,
                    negated = negated,
                    predicate = predicateExpression,
                ),
            )
        },
        modifier = modifier,
        enabled = editor.filtersEditable,
    )
}

@Composable
private fun DescriptorTextField(
    label: String,
    value: String,
    editor: ListEditorState,
    capability: CollectionFilterCapability,
    negated: Boolean,
    onEditorChange: (ListEditorState) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        modifier = modifier.fillMaxWidth(),
        value = value,
        onValueChange = { candidate ->
            val predicate = candidate.trim().takeIf(String::isNotEmpty)?.let {
                QueryExpression.Predicate(
                    field = capability.field,
                    operator = capability.preferredScalarOperator(),
                    value = QueryValue.of(it),
                )
            }
            onEditorChange(
                editor.replaceFieldTerm(
                    field = capability.field,
                    negated = negated,
                    predicate = predicate,
                ),
            )
        },
        enabled = editor.filtersEditable,
        label = { Text(label) },
        singleLine = true,
    )
}

@Composable
private fun RemoteLookupFilterControl(
    providerId: String,
    capability: CollectionFilterCapability,
    source: FilterValueSource.RemoteLookup,
    positivePredicates: List<QueryExpression.Predicate>,
    negativePredicates: List<QueryExpression.Predicate>,
    editor: ListEditorState,
    onEditorChange: (ListEditorState) -> Unit,
    lookupState: CollectionFilterLookupState?,
    onFilterLookup: (String, String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember(providerId, source.lookupId) { mutableStateOf("") }

    LaunchedEffect(providerId, source.lookupId, query) {
        onFilterLookup(providerId, source.lookupId, query)
    }

    Column(modifier = modifier) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = query,
            onValueChange = { query = it },
            enabled = editor.filtersEditable,
            label = { Text("Search ${capability.id.prettyEnumName()}") },
            singleLine = true,
        )

        when (lookupState) {
            null,
            CollectionFilterLookupState.Idle,
            -> Unit

            is CollectionFilterLookupState.Loading -> {
                Text(
                    text = "Loading options…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            is CollectionFilterLookupState.Error -> {
                Text(
                    text = lookupState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            is CollectionFilterLookupState.Ready -> {
                if (lookupState.options.isEmpty()) {
                    Text(
                        text = "No matching options",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    val positiveValues = positivePredicates.map { it.value }.toSet()
                    val negativeValues = negativePredicates.map { it.value }.toSet()
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        items(lookupState.options, key = { option -> option.id }) { option ->
                            val selectedPositive = option.value in positiveValues
                            FilterChip(
                                selected = selectedPositive,
                                enabled = editor.filtersEditable,
                                onClick = {
                                    val predicate = if (selectedPositive) null else QueryExpression.Predicate(
                                        field = capability.field,
                                        operator = capability.preferredScalarOperator(),
                                        value = option.value,
                                    )
                                    onEditorChange(
                                        if (capability.multiValueMode == MultiValueMode.ALL) {
                                            editor.toggleFieldPredicate(
                                                field = capability.field,
                                                negated = false,
                                                predicate = QueryExpression.Predicate(
                                                    field = capability.field,
                                                    operator = capability.preferredScalarOperator(),
                                                    value = option.value,
                                                ),
                                            )
                                        } else {
                                            editor.replaceFieldTerm(
                                                field = capability.field,
                                                negated = false,
                                                predicate = predicate,
                                            )
                                        },
                                    )
                                },
                                label = { Text(option.label) },
                            )

                            if (capability.multiValueMode == MultiValueMode.INCLUDE_EXCLUDE) {
                                val selectedNegative = option.value in negativeValues
                                FilterChip(
                                    selected = selectedNegative,
                                    enabled = editor.filtersEditable,
                                    onClick = {
                                        val predicate = if (selectedNegative) null else QueryExpression.Predicate(
                                            field = capability.field,
                                            operator = capability.preferredScalarOperator(),
                                            value = option.value,
                                        )
                                        onEditorChange(
                                            editor.replaceFieldTerm(
                                                field = capability.field,
                                                negated = true,
                                                predicate = predicate,
                                            ),
                                        )
                                    },
                                    label = { Text("Exclude ${option.label}") },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun CollectionProviderDescriptor.uiSortSelections(): List<CollectionSortSelection> =
    sorts.flatMap { capability ->
        when (capability.directionMode) {
            SortDirectionMode.ASC_DESC -> {
                val default = requireNotNull(capability.defaultDirection)
                val alternate = if (default == CollectionSortDirection.DESC) {
                    CollectionSortDirection.ASC
                } else {
                    CollectionSortDirection.DESC
                }
                listOf(
                    CollectionSortSelection(capability.key, default),
                    CollectionSortSelection(capability.key, alternate),
                )
            }
            SortDirectionMode.ASC_ONLY -> listOf(
                CollectionSortSelection(capability.key, CollectionSortDirection.ASC),
            )
            SortDirectionMode.DESC_ONLY -> listOf(
                CollectionSortSelection(capability.key, CollectionSortDirection.DESC),
            )
            SortDirectionMode.FIXED_NATIVE -> listOf(
                CollectionSortSelection(capability.key, direction = null),
            )
        }
    }.distinct()

private fun CollectionProviderDescriptor.sortDisplayName(
    selection: CollectionSortSelection,
): String {
    val capability = sorts.firstOrNull { it.key == selection.key }
        ?: return sortDisplayName(selection)
    return when (capability.directionMode) {
        SortDirectionMode.ASC_DESC -> when (selection.direction) {
            CollectionSortDirection.DESC -> "${capability.label} (High to Low)"
            CollectionSortDirection.ASC -> "${capability.label} (Low to High)"
            null -> capability.label
        }
        else -> capability.label
    }
}

internal fun listBuilderProviderIds(
    descriptors: List<CollectionProviderDescriptor>,
    currentProviderId: String,
): List<String> = (
    descriptors.map(CollectionProviderDescriptor::providerId) +
        currentProviderId.takeIf { it.isNotBlank() }.orEmpty()
    ).filter(String::isNotBlank).distinct()

internal fun CollectionProviderDescriptor.visibleFilterFields(
    placement: FilterPlacement,
): List<QueryField> = filters
    .filter { it.placement == placement }
    .map(CollectionFilterCapability::field)

private fun CollectionFilterCapability.preferredScalarOperator(): QueryOperator = when {
    QueryOperator.EQUALS in operators -> QueryOperator.EQUALS
    QueryOperator.CONTAINS in operators -> QueryOperator.CONTAINS
    QueryOperator.GREATER_OR_EQUAL in operators -> QueryOperator.GREATER_OR_EQUAL
    else -> operators.first()
}

private fun ListEditorState.simplePredicates(
    field: QueryField,
    negated: Boolean,
): List<QueryExpression.Predicate> {
    val terms = when (val query = currentQuery()) {
        null -> emptyList()
        is QueryExpression.All -> query.expressions
        else -> listOf(query)
    }
    return terms.mapNotNull { term ->
        when {
            !negated && term is QueryExpression.Predicate && term.field == field -> term
            negated && term is QueryExpression.Not -> {
                (term.expression as? QueryExpression.Predicate)?.takeIf { it.field == field }
            }
            else -> null
        }
    }
}

private fun ListEditorState.simplePredicate(
    field: QueryField,
    negated: Boolean,
): QueryExpression.Predicate? = simplePredicates(field, negated).firstOrNull()

private fun ListEditorState.replaceFieldTerm(
    field: QueryField,
    negated: Boolean,
    predicate: QueryExpression.Predicate?,
): ListEditorState = replaceFieldTerms(
    field = field,
    negated = negated,
    predicates = listOfNotNull(predicate),
)

private fun ListEditorState.toggleFieldPredicate(
    field: QueryField,
    negated: Boolean,
    predicate: QueryExpression.Predicate,
): ListEditorState {
    val existing = simplePredicates(field, negated)
    val selected = existing.any { candidate ->
        candidate.operator == predicate.operator && candidate.value == predicate.value
    }
    val next = if (selected) {
        existing.filterNot { candidate ->
            candidate.operator == predicate.operator && candidate.value == predicate.value
        }
    } else {
        existing + predicate
    }
    return replaceFieldTerms(field, negated, next)
}

private fun ListEditorState.replaceFieldTerms(
    field: QueryField,
    negated: Boolean,
    predicates: List<QueryExpression.Predicate>,
): ListEditorState {
    var updated = this
    updated = when (field) {
        QueryField.STATUS -> if (!negated) updated.copy(status = null) else updated
        QueryField.WORK_TYPE -> if (!negated) updated.copy(format = null) else updated
        QueryField.GENRE -> if (negated) {
            updated.copy(excludeGenre = "")
        } else {
            updated.copy(includeGenre = "")
        }
        QueryField.TAG -> if (negated) {
            updated.copy(excludeTag = "")
        } else {
            updated.copy(includeTag = "")
        }
        QueryField.SCORE,
        QueryField.RATING,
        -> if (!negated) updated.copy(minScore = "") else updated
        QueryField.CHAPTER_COUNT -> if (!negated) updated.copy(minChapters = "") else updated
        QueryField.VOLUME_COUNT -> if (!negated) updated.copy(minVolumes = "") else updated
        else -> updated
    }

    val remaining = updated.extraTerms.filterNot { term ->
        term.matchesFieldPolarity(field = field, negated = negated)
    }
    val replacements = predicates.map { predicate ->
        if (negated) QueryExpression.Not(predicate) else predicate
    }

    return updated.copy(extraTerms = remaining + replacements)
}

private fun QueryExpression.matchesFieldPolarity(
    field: QueryField,
    negated: Boolean,
): Boolean = when {
    !negated && this is QueryExpression.Predicate -> this.field == field
    negated && this is QueryExpression.Not ->
        (expression as? QueryExpression.Predicate)?.field == field
    else -> false
}

private fun QueryExpression.Predicate?.toNumericBounds(): Pair<String, String> {
    if (this == null) return "" to ""
    return when (operator) {
        QueryOperator.GREATER_THAN,
        QueryOperator.GREATER_OR_EQUAL,
        QueryOperator.EQUALS,
        -> value.numericText().orEmpty() to ""
        QueryOperator.LESS_THAN,
        QueryOperator.LESS_OR_EQUAL,
        -> "" to value.numericText().orEmpty()
        QueryOperator.BETWEEN -> {
            val range = value as? QueryValue.RangeValue ?: return "" to ""
            range.lower.numericText().orEmpty() to range.upper.numericText().orEmpty()
        }
        else -> "" to ""
    }
}

private fun QueryExpression.Predicate?.toStringBounds(): Pair<String, String> {
    if (this == null) return "" to ""
    return when (operator) {
        QueryOperator.GREATER_THAN,
        QueryOperator.GREATER_OR_EQUAL,
        QueryOperator.EQUALS,
        -> (value as? QueryValue.StringValue)?.value.orEmpty() to ""
        QueryOperator.LESS_THAN,
        QueryOperator.LESS_OR_EQUAL,
        -> "" to (value as? QueryValue.StringValue)?.value.orEmpty()
        QueryOperator.BETWEEN -> {
            val range = value as? QueryValue.RangeValue ?: return "" to ""
            (range.lower as? QueryValue.StringValue)?.value.orEmpty() to
                (range.upper as? QueryValue.StringValue)?.value.orEmpty()
        }
        else -> "" to ""
    }
}

private fun CollectionFilterCapability.rangeExpression(
    minimum: String,
    maximum: String,
    decimal: Boolean,
): QueryExpression? {
    val min = minimum.takeIf(String::isNotBlank)?.let { if (decimal) it.toDoubleOrNull() else it.toLongOrNull() }
    val max = maximum.takeIf(String::isNotBlank)?.let { if (decimal) it.toDoubleOrNull() else it.toLongOrNull() }
    val minValue = when (min) {
        is Double -> QueryValue.of(min)
        is Long -> QueryValue.of(min)
        else -> null
    }
    val maxValue = when (max) {
        is Double -> QueryValue.of(max)
        is Long -> QueryValue.of(max)
        else -> null
    }
    return when {
        minValue != null && maxValue != null && QueryOperator.BETWEEN in operators ->
            QueryExpression.Predicate(field, QueryOperator.BETWEEN, QueryValue.range(minValue, maxValue))
        minValue != null && QueryOperator.GREATER_OR_EQUAL in operators ->
            QueryExpression.Predicate(field, QueryOperator.GREATER_OR_EQUAL, minValue)
        maxValue != null && QueryOperator.LESS_OR_EQUAL in operators ->
            QueryExpression.Predicate(field, QueryOperator.LESS_OR_EQUAL, maxValue)
        else -> null
    }
}

private fun CollectionFilterCapability.stringRangeExpression(
    minimum: String,
    maximum: String,
): QueryExpression? {
    val min = minimum.trim().takeIf(String::isNotEmpty)?.let { QueryValue.of(it) }
    val max = maximum.trim().takeIf(String::isNotEmpty)?.let { QueryValue.of(it) }
    return when {
        min != null && max != null && QueryOperator.BETWEEN in operators ->
            QueryExpression.Predicate(field, QueryOperator.BETWEEN, QueryValue.range(min, max))
        min != null && QueryOperator.GREATER_OR_EQUAL in operators ->
            QueryExpression.Predicate(field, QueryOperator.GREATER_OR_EQUAL, min)
        max != null && QueryOperator.LESS_OR_EQUAL in operators ->
            QueryExpression.Predicate(field, QueryOperator.LESS_OR_EQUAL, max)
        else -> null
    }
}

private fun QueryValue.numericText(): String? = when (this) {
    is QueryValue.IntegerValue -> value.toString()
    is QueryValue.DoubleValue -> value.toString()
    else -> null
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
).count { it != null } + extraTerms.size

private fun providerDisplayName(providerId: String): String = providerId.prettyEnumName()

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

private const val PREVIEW_TITLE_LIMIT = 4
