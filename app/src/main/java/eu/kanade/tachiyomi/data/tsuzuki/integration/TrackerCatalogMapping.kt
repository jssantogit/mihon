package eu.kanade.tachiyomi.data.tsuzuki.integration

import eu.kanade.tachiyomi.data.track.model.TrackSearch
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.catalog.model.CatalogScore

internal fun TrackSearch.toIntegrationCatalogItem(providerId: String): CatalogItem = CatalogItem(
    provider = providerId,
    providerId = remote_id.toString(),
    title = title,
    titles = alternate_titles
        .filter(String::isNotBlank)
        .distinct()
        .mapIndexed { index, value -> "alternate_$index" to value }
        .toMap(),
    synopsis = summary.ifBlank { null },
    coverUrl = cover_url.ifBlank { null },
    score = score
        .takeIf { it > 0.0 }
        ?.let {
            CatalogScore(
                provider = providerId,
                value = it,
                maxValue = 10.0,
                voteCount = score_votes,
            )
        },
    authors = authors.filter(String::isNotBlank).distinct(),
    artists = artists.filter(String::isNotBlank).distinct(),
    genres = genres.filter(String::isNotBlank).distinct(),
    tags = tags.filter(String::isNotBlank).distinct(),
    status = publishing_status.toCatalogStatus(),
    format = publishing_type.toCatalogFormat(),
    startDate = start_date.ifBlank { null },
    endDate = end_date.ifBlank { null },
    chapterCount = total_chapters
        .takeIf { it > 0 && it <= Int.MAX_VALUE }
        ?.toInt(),
    volumeCount = total_volumes
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

private fun String.toCatalogStatus(): CatalogItemStatus {
    val normalized = lowercase().replace('_', ' ').trim()
    return when {
        normalized.isBlank() -> CatalogItemStatus.UNKNOWN
        "hiatus" in normalized -> CatalogItemStatus.ON_HIATUS
        "cancel" in normalized || "discontinued" in normalized -> CatalogItemStatus.CANCELLED
        "complete" in normalized || "finished" in normalized -> CatalogItemStatus.COMPLETED
        "ongoing" in normalized || "publishing" in normalized || normalized == "current" ->
            CatalogItemStatus.ONGOING
        else -> CatalogItemStatus.UNKNOWN
    }
}
