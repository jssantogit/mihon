package eu.kanade.presentation.tsuzuki.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.tsuzuki.integration.IntegrationBrandIcon
import eu.kanade.presentation.tsuzuki.integration.ratingPercentageLabel
import eu.kanade.tachiyomi.ui.tsuzuki.detail.CanonicalChapterDetailItem
import eu.kanade.tachiyomi.ui.tsuzuki.detail.CanonicalProviderRating
import eu.kanade.tachiyomi.ui.tsuzuki.detail.CanonicalTitleScreenState
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun CanonicalTitleDetailScreen(
    state: CanonicalTitleScreenState,
    navigateUp: () -> Unit,
    onAtualizar: () -> Unit,
    onAddToLibrary: () -> Unit,
    onRemoveFromLibrary: () -> Unit,
    onOpenChapter: (String) -> Unit,
    onBaixarChapter: (String) -> Unit,
    onOpenAddonsSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Detalhes") },
                navigationIcon = {
                    TextButton(onClick = navigateUp) {
                        Text("Voltar")
                    }
                },
                actions = {
                    TextButton(onClick = onAtualizar) {
                        Text("Atualizar")
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
                        text = state.error.message ?: "Não foi possível carregar o título.",
                    )
                    TextButton(onClick = onAtualizar) {
                        Text("Tentar novamente")
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
                            onAddToLibrary = onAddToLibrary,
                            onRemoveFromLibrary = onRemoveFromLibrary,
                        )
                    }
                    if (state.chapters.isEmpty()) {
                        item {
                            Text(
                                text = if (state.isRefreshing) {
                                    "Carregando capítulos…"
                                } else {
                                    "Nenhum capítulo encontrado."
                                },
                                modifier = Modifier.padding(16.dp),
                            )
                            if (!state.isRefreshing) {
                                TextButton(onClick = onOpenAddonsSettings) {
                                    Text("Gerenciar Add-ons de leitura")
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
                                onBaixarChapter = onBaixarChapter,
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
    onAddToLibrary: () -> Unit,
    onRemoveFromLibrary: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            MangaCover.Book(
                data = state.coverUrl,
                contentDescription = state.title.displayTitle,
                modifier = Modifier.width(112.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = state.title.displayTitle,
                    style = MaterialTheme.typography.headlineSmall,
                )
                state.author?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = state.libraryEntry?.status?.name?.replace('_', ' ') ?: "Fora da Biblioteca",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val editorialFacts = buildList {
                    state.editorialFormat?.toEditorialFormatLabel()?.let { add(it) }
                    state.editorialStatus?.toEditorialStatusLabel()?.let { add(it) }
                    state.editorialVolumeCount?.let { add("$it volumes") }
                    state.metadataDateLabel()?.let { add(it) }
                }
                if (editorialFacts.isNotEmpty()) {
                    Text(
                        text = editorialFacts.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.ratings.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        state.ratings.forEach { rating ->
                            ProviderRatingBadge(rating)
                        }
                    }
                }
                Button(
                    enabled = !state.libraryMutationInProgress,
                    onClick = if (state.libraryEntry == null) onAddToLibrary else onRemoveFromLibrary,
                ) {
                    Text(if (state.libraryEntry == null) "Adicionar à Biblioteca" else "Remover da Biblioteca")
                }
            }
        }
        if (state.genres.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.genres.take(3).forEach { genre ->
                    SuggestionChip(onClick = {}, label = { Text(genre) })
                }
            }
        }
        if (state.tags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.tags.take(3).forEach { tag ->
                    AssistChip(onClick = {}, label = { Text(tag) })
                }
            }
        }
        state.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 5,
            )
        }
        if (state.metadataSources.isNotEmpty()) {
            Text(
                text = stringResource(MR.strings.tsuzuki_metadata_sources, state.metadataSources.joinToString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.isRefreshing) {
            Text("Atualizando capítulos…", style = MaterialTheme.typography.bodySmall)
        }
        state.libraryMutationError?.let {
            Text(
                text = it.message ?: "Não foi possível atualizar a Biblioteca.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        state.refreshError?.let {
            Text(
                text = "A atualização de capítulos está temporariamente indisponível.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        state.downloadError?.let {
            Text(
                text = it.message ?: "Não foi possível baixar o capítulo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        state.chapterActionError?.let {
            Text(
                text = it.message ?: "Não foi possível abrir o capítulo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = "Capítulos",
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun CanonicalChapterRow(
    item: CanonicalChapterDetailItem,
    downloading: Boolean,
    onOpenChapter: (String) -> Unit,
    onBaixarChapter: (String) -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = "Capítulo ${item.chapter.displayNumber}",
            )
        },
        supportingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (item.progress?.read == true) {
                        "Lido"
                    } else {
                        "Não lido"
                    },
                )
                if (item.downloaded) {
                    Text("Baixado")
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
                            label = { Text("Conflito") },
                        )
                    }

                    CanonicalChapterConfirmation.CONFIRMED -> Unit
                }
                TextButton(
                    enabled = !item.downloaded && !downloading,
                    onClick = { onBaixarChapter(item.chapter.id) },
                ) {
                    Text(
                        when {
                            item.downloaded -> "Baixado"
                            downloading -> "Baixando…"
                            else -> "Baixar"
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

@Composable
private fun ProviderRatingBadge(
    rating: CanonicalProviderRating,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IntegrationBrandIcon(
            providerId = rating.providerId,
            size = 20.dp,
        )
        Text(
            text = ratingPercentageLabel(
                value = rating.value,
                maxValue = rating.maxValue,
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun String.toEditorialStatusLabel(): String? = when (this) {
    "ONGOING" -> "Em publicação"
    "COMPLETED" -> "Concluído"
    "ON_HIATUS" -> "Em hiato"
    "CANCELLED" -> "Cancelado"
    else -> null
}

private fun String.toEditorialFormatLabel(): String? = when (this) {
    "MANGA" -> "Mangá"
    "ONE_SHOT" -> "One-shot"
    "MANHWA" -> "Manhwa"
    "MANHUA" -> "Manhua"
    "DOUJIN" -> "Doujin"
    "NOVEL" -> "Novel"
    else -> null
}

private fun CanonicalTitleScreenState.Loaded.metadataDateLabel(): String? = when {
    !startDate.isNullOrBlank() && !endDate.isNullOrBlank() -> "$startDate – $endDate"
    !startDate.isNullOrBlank() -> startDate
    !endDate.isNullOrBlank() -> endDate
    else -> null
}
