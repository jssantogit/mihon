package eu.kanade.presentation.tsuzuki.integration

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R

@Composable
fun IntegrationBrandIcon(
    providerId: String,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    val drawable = integrationBrandDrawable(providerId) ?: return
    Image(
        painter = painterResource(drawable),
        contentDescription = integrationDisplayName(providerId),
        modifier = modifier
            .size(size)
            .clip(MaterialTheme.shapes.small),
    )
}

@DrawableRes
internal fun integrationBrandDrawable(providerId: String): Int? = when (providerId.lowercase()) {
    "mal" -> R.drawable.brand_myanimelist
    "kitsu" -> R.drawable.brand_kitsu
    "mangaupdates" -> R.drawable.brand_mangaupdates
    "bangumi" -> R.drawable.brand_bangumi
    "shikimori" -> R.drawable.brand_shikimori
    "hikka" -> R.drawable.brand_hikka
    else -> null
}

internal fun integrationDisplayName(providerId: String): String = when (providerId.lowercase()) {
    "mal" -> "MyAnimeList"
    "kitsu" -> "Kitsu"
    "mangaupdates" -> "MangaUpdates"
    "bangumi" -> "Bangumi"
    "shikimori" -> "Shikimori"
    "hikka" -> "Hikka"
    else -> providerId
}

internal fun ratingScaleLabel(
    providerId: String,
    value: Double,
    maxValue: Double,
): String {
    val displayedValue = value.toCompactRatingNumber()
    if (providerId.lowercase() in PERCENT_RATING_PROVIDERS && maxValue == 100.0) {
        return "$displayedValue%"
    }
    if (maxValue <= 0.0) {
        return displayedValue
    }
    return "$displayedValue/${maxValue.toCompactRatingNumber()}"
}

private fun Double.toCompactRatingNumber(): String =
    if (isFinite() && this % 1.0 == 0.0) {
        toLong().toString()
    } else {
        toString()
    }

private val PERCENT_RATING_PROVIDERS = setOf("kitsu")
