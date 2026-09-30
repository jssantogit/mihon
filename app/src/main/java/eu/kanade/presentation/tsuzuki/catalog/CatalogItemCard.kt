package eu.kanade.presentation.tsuzuki.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.tsuzuki.integration.IntegrationBrandIcon
import eu.kanade.presentation.tsuzuki.integration.ratingScaleLabel
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogScore

@Composable
fun CatalogItemCard(
    item: CatalogItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MangaCover.Book(
                data = item.coverUrl,
                contentDescription = item.title,
                modifier = Modifier.width(64.dp),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (item.format != CatalogItemFormat.UNKNOWN) {
                    Text(
                        text = item.format.name.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                CatalogRatingBadges(
                    scores = item.scores.ifEmpty { listOfNotNull(item.score) },
                    modifier = Modifier.padding(top = 2.dp),
                )

                item.synopsis?.let { synopsis ->
                    Text(
                        text = synopsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun CatalogCompactCard(
    item: CatalogItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(120.dp)
            .clickable(onClick = onClick),
    ) {
        MangaCover.Book(
            data = item.coverUrl,
            contentDescription = item.title,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        CatalogRatingBadges(
            scores = item.scores.ifEmpty { listOfNotNull(item.score) },
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun CatalogRatingBadges(
    scores: List<CatalogScore>,
    modifier: Modifier = Modifier,
) {
    if (scores.isEmpty()) return

    Column(
        modifier = modifier,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
    ) {
        scores.forEach { score ->
            CatalogRatingBadge(score = score)
        }
    }
}

@Composable
private fun CatalogRatingBadge(
    score: CatalogScore,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IntegrationBrandIcon(
            providerId = score.provider,
            size = 16.dp,
        )
        Text(
            text = catalogRatingLabel(score),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
    }
}

internal fun catalogRatingLabel(score: CatalogScore): String =
    ratingScaleLabel(
        providerId = score.provider,
        value = score.value,
        maxValue = score.maxValue,
    )
