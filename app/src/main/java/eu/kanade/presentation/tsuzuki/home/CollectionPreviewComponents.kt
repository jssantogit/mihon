package eu.kanade.presentation.tsuzuki.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem

@Composable
internal fun CoverPreviewStrip(
    items: List<CatalogItem>,
    modifier: Modifier = Modifier,
    slotWidth: Dp = 88.dp,
    slotCount: Int = 4,
    showPlaceholders: Boolean = true,
) {
    val visible = items.take(slotCount)
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        userScrollEnabled = false,
    ) {
        items(
            items = visible,
            key = { item -> "${item.provider}:${item.providerId}" },
        ) { item ->
            MangaCover.Book(
                data = item.coverUrl,
                contentDescription = item.title,
                modifier = Modifier.width(slotWidth),
            )
        }

        if (showPlaceholders) {
            items((slotCount - visible.size).coerceAtLeast(0)) { index ->
                Box(
                    modifier = Modifier
                        .width(slotWidth)
                        .aspectRatio(MangaCover.Book.ratio)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
    }
}
