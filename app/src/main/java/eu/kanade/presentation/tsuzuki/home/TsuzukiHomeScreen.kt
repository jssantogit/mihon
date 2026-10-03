package eu.kanade.presentation.tsuzuki.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.tsuzuki.recordArtworkLoad
import eu.kanade.tachiyomi.ui.tsuzuki.home.TsuzukiHomeScreenState
import mihon.app.di.appGraph
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticTrace
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticWorkflow
import tachiyomi.domain.tsuzuki.home.model.HomeContinueReadingItem
import tachiyomi.domain.tsuzuki.home.model.HomeSection
import tachiyomi.presentation.core.components.material.Scaffold

@Composable
fun TsuzukiHomeScreen(
    state: TsuzukiHomeScreenState,
    onContinueReading: (HomeContinueReadingItem) -> Unit,
    onCollection: (collectionId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            AppBar(
                titleContent = { AppBarTitle("Início") },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            state.continueReading.firstOrNull()?.let { hero ->
                item(key = "hero") {
                    HeroCard(
                        item = hero,
                        onClick = { onContinueReading(hero) },
                    )
                }
            }

            state.sections.forEach { section ->
                when (section) {
                    is HomeSection.CollectionSection -> {
                        item(key = "collection_${section.collectionId}") {
                            HomeCollectionSection(
                                section = section,
                                onClick = { onCollection(section.collectionId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(
    item: HomeContinueReadingItem,
    onClick: () -> Unit,
) {
    val recorder = LocalContext.current.appGraph.structuredDiagnosticRecorder
    val trace = remember(item.canonicalTitleId, recorder.sessionId) {
        DiagnosticTrace.start(
            recorder = recorder,
            workflow = DiagnosticWorkflow.CONTINUE_READING,
            canonicalTitleId = item.canonicalTitleId,
            subsystem = DiagnosticSubsystem.IMAGE,
        )
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MangaCover.Book(
                data = item.coverUrl ?: item.sourceCover,
                fallbackData = listOf(item.sourceCover, item.sourceCover?.url),
                contentDescription = item.title,
                modifier = Modifier.width(96.dp),
                onLoadEvent = { trace.recordArtworkLoad(it) },
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Continuar lendo",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Capítulo ${item.chapterDisplayNumber}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun HomeCollectionSection(
    section: HomeSection.CollectionSection,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 22.dp, bottom = 12.dp),
    ) {
        Text(
            text = section.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        CollectionCoverWindow(
            items = section.previewItems,
            onClick = onClick,
        )
    }
}

@Composable
private fun CollectionCoverWindow(
    items: List<CatalogItem>,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        LazyRow(
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            userScrollEnabled = false,
        ) {
            if (items.isEmpty()) {
                items(COLLECTION_PREVIEW_SLOTS) {
                    CollectionCoverPlaceholder()
                }
            } else {
                items(
                    items = items.take(COLLECTION_PREVIEW_SLOTS),
                    key = { "${it.provider}:${it.providerId}" },
                ) { item ->
                    MangaCover.Book(
                        data = item.coverUrl,
                        contentDescription = null,
                        modifier = Modifier.width(COLLECTION_PREVIEW_COVER_WIDTH),
                    )
                }
                if (items.size < COLLECTION_PREVIEW_SLOTS) {
                    items(COLLECTION_PREVIEW_SLOTS - items.size) {
                        CollectionCoverPlaceholder()
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionCoverPlaceholder() {
    Box(
        modifier = Modifier
            .width(COLLECTION_PREVIEW_COVER_WIDTH)
            .aspectRatio(2f / 3f)
            .background(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
                shape = MaterialTheme.shapes.small,
            ),
    )
}

private const val COLLECTION_PREVIEW_SLOTS = 4
private val COLLECTION_PREVIEW_COVER_WIDTH = 78.dp
