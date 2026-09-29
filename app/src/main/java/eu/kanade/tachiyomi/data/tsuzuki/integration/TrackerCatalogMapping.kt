package eu.kanade.tachiyomi.data.tsuzuki.integration

import eu.kanade.tachiyomi.data.track.model.TrackSearch
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogScore

internal fun TrackSearch.toIntegrationCatalogItem(providerId: String): CatalogItem = CatalogItem(
    provider = providerId,
    providerId = remote_id.toString(),
    title = title,
    synopsis = summary.ifBlank { null },
    coverUrl = cover_url.ifBlank { null },
    score = score
        .takeIf { it > 0.0 }
        ?.let { CatalogScore(provider = providerId, value = it, maxValue = 10.0) },
    authors = authors.filter(String::isNotBlank).distinct(),
    artists = artists.filter(String::isNotBlank).distinct(),
    format = publishing_type.toCatalogFormat(),
    startDate = start_date.ifBlank { null },
    chapterCount = total_chapters
        .takeIf { it > 0 && it <= Int.MAX_VALUE }
        ?.toInt(),
)


private fun String.toCatalogFormat(): CatalogItemFormat = when (
    lowercase().replace('_', ' ').trim()
) {
    "manga" -> CatalogItemFormat.MANGA
    "one shot", "oneshot" -> CatalogItemFormat.ONE_SHOT
    "manhwa" -> CatalogItemFormat.MANHWA
    "manhua" -> CatalogItemFormat.MANHUA
    "doujin", "doujinshi" -> CatalogItemFormat.DOUJIN
    "novel", "light novel" -> CatalogItemFormat.NOVEL
    else -> CatalogItemFormat.UNKNOWN
}
