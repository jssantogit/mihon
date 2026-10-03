package eu.kanade.presentation.tsuzuki.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.tsuzuki.recordArtworkLoad
import eu.kanade.tachiyomi.ui.tsuzuki.home.TsuzukiHomeScreenState
import kotlinx.coroutines.delay
import mihon.app.di.appGraph
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticTrace
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticWorkflow
import tachiyomi.domain.tsuzuki.home.model.HomeContinueReadingItem
import tachiyomi.domain.tsuzuki.home.model.HomeSection
import tachiyomi.presentation.core.components.material.Scaffold

@Composable
fun TsuzukiHomeScreen(
    state: TsuzukiHomeScreenState,
    onHeroRead: (CatalogItem) -> Unit,
    onHeroDetails: (CatalogItem) -> Unit,
    onContinueReading: (HomeContinueReadingItem) -> Unit,
    onFolder: (collectionId: String, folderId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (state.heroes.isNotEmpty()) {
                item(key = "home_hero") {
                    HomeHeroCarousel(
                        items = state.heroes,
                        onRead = onHeroRead,
                        onDetails = onHeroDetails,
                    )
                }
            } else {
                item(key = "home_header") {
                    HomeHeader()
                }
            }

            state.continueReading.firstOrNull()?.let { item ->
                item(key = "continue_reading") {
                    ContinueReadingCard(
                        item = item,
                        onClick = { onContinueReading(item) },
                    )
                }
            }

            state.sections.forEach { section ->
                when (section) {
                    is HomeSection.CollectionSection -> {
                        item(key = "collection_${section.collectionId}") {
                            CollectionHomeSection(
                                section = section,
                                onFolder = { folderId ->
                                    onFolder(section.collectionId, folderId)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeader() {
    Text(
        text = "Início",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 16.dp),
    )
}

@Composable
private fun HomeHeroCarousel(
    items: List<CatalogItem>,
    onRead: (CatalogItem) -> Unit,
    onDetails: (CatalogItem) -> Unit,
) {
    var activeIndex by remember(items) { mutableIntStateOf(0) }

    LaunchedEffect(items) {
        activeIndex = 0
        if (items.size <= 1) return@LaunchedEffect

        while (true) {
            delay(HERO_ROTATION_INTERVAL_MILLIS)
            activeIndex = (activeIndex + 1) % items.size
        }
    }

    val activeItem = items[activeIndex.coerceIn(items.indices)]

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(HERO_ASPECT_RATIO),
    ) {
        Crossfade(
            targetState = activeItem,
            animationSpec = tween(durationMillis = HERO_CROSSFADE_MILLIS),
            modifier = Modifier.fillMaxSize(),
        ) { item ->
            HomeHero(
                item = item,
                activeIndex = activeIndex,
                itemCount = items.size,
                onRead = { onRead(item) },
                onDetails = { onDetails(item) },
            )
        }

        Text(
            text = "Início",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
        )
    }
}

@Composable
private fun HomeHero(
    item: CatalogItem,
    activeIndex: Int,
    itemCount: Int,
    onRead: () -> Unit,
    onDetails: () -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    val metadata = buildList {
        if (item.format != CatalogItemFormat.UNKNOWN) {
            add(
                item.format.name
                    .lowercase()
                    .replace('_', ' ')
                    .replaceFirstChar { it.uppercase() },
            )
        }
        item.genres.firstOrNull()
            ?.takeIf(String::isNotBlank)
            ?.let(::add)
        if (item.status != CatalogItemStatus.UNKNOWN) {
            add(
                item.status.name
                    .lowercase()
                    .replace('_', ' ')
                    .replaceFirstChar { it.uppercase() },
            )
        }
    }.take(3).joinToString(" · ")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background),
    ) {
        AsyncImage(
            model = item.bannerUrl ?: item.coverUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.18f),
                        0.34f to Color.Transparent,
                        0.70f to Color.Black.copy(alpha = 0.48f),
                        1.0f to background,
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (metadata.isNotBlank()) {
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            item.synopsis
                ?.takeIf(String::isNotBlank)
                ?.let { synopsis ->
                    Text(
                        text = synopsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.82f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onRead,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Ler")
                }
                OutlinedButton(
                    onClick = onDetails,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Detalhes")
                }
            }

            if (itemCount > 1) {
                Text(
                    text = (0 until itemCount).joinToString("  ") { index ->
                        if (index == activeIndex) "●" else "•"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.76f),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

@Composable
private fun ContinueReadingCard(
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
            .padding(horizontal = 16.dp)
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
private fun CollectionHomeSection(
    section: HomeSection.CollectionSection,
    onFolder: (folderId: String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = section.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(
                items = section.folders,
                key = { folder -> folder.folderId },
            ) { folder ->
                FolderTile(
                    folder = folder,
                    onClick = { onFolder(folder.folderId) },
                )
            }
        }
    }
}

private const val HERO_ASPECT_RATIO = 0.78f
private const val HERO_ROTATION_INTERVAL_MILLIS = 8_000L
private const val HERO_CROSSFADE_MILLIS = 650
