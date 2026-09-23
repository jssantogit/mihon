package eu.kanade.tachiyomi.data.tsuzuki.integration

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.data.track.myanimelist.MalIntegrationApi
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.model.CatalogScore
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.model.ExternalRating
import kotlin.coroutines.cancellation.CancellationException

@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<SearchProvider>())
@ContributesIntoSet(AppScope::class, binding = binding<MetadataProvider>())
@ContributesIntoSet(AppScope::class, binding = binding<RatingsProvider>())
class MalIntegrationProvider private constructor(
    private val api: MalIntegrationApi,
) : SearchProvider, MetadataProvider, RatingsProvider {

    @Inject
    constructor(trackerManager: TrackerManager) : this(trackerManager.myAnimeList.integrationApi)

    override val integrationId = IntegrationId("mal")

    override suspend fun search(query: CatalogQuery): Result<CatalogPage> {
        val searchText = query.query?.trim().orEmpty()
        if (searchText.isEmpty()) {
            return Result.success(CatalogPage(items = emptyList(), hasNextPage = false))
        }

        return captureResult {
            val results = api.search(searchText)
            val offset = query.offset.coerceAtLeast(0)
            val limit = query.limit.coerceAtLeast(0)
            val pageItems = results
                .drop(offset)
                .take(limit)
                .map { it.toCatalogItem() }

            CatalogPage(
                items = pageItems,
                hasNextPage = offset + pageItems.size < results.size,
                totalCount = results.size,
            )
        }
    }

    override suspend fun getDetails(externalId: String): Result<CatalogItem> {
        return captureResult {
            api.getMangaDetails(externalId.requireMalId()).toCatalogItem()
        }
    }

    override suspend fun ratings(externalId: String): Result<List<ExternalRating>> {
        return captureResult {
            val details = api.getMangaDetails(externalId.requireMalId())
            listOfNotNull(details.toExternalRating())
        }
    }

    private fun TrackSearch.toCatalogItem(): CatalogItem {
        return CatalogItem(
            provider = integrationId.value,
            providerId = remote_id.toString(),
            title = title,
            synopsis = summary.ifBlank { null },
            coverUrl = cover_url.ifBlank { null },
            status = publishing_status.toCatalogStatus(),
            format = publishing_type.toCatalogFormat(),
            score = score
                .takeIf { it >= 0.0 }
                ?.let { value ->
                    CatalogScore(
                        provider = integrationId.value,
                        value = value,
                        maxValue = MAL_SCORE_MAX,
                    )
                },
            startDate = start_date.ifBlank { null },
            chapterCount = total_chapters
                .takeIf { it > 0 && it <= Int.MAX_VALUE }
                ?.toInt(),
        )
    }

    private fun TrackSearch.toExternalRating(): ExternalRating? {
        val value = score.takeIf { it >= 0.0 } ?: return null
        return ExternalRating(
            providerId = integrationId.value,
            label = "MAL",
            value = value,
            scaleMax = MAL_SCORE_MAX,
        )
    }

    private fun String.toCatalogStatus(): CatalogItemStatus = when (normalizedMalValue()) {
        "currently publishing", "publishing" -> CatalogItemStatus.ONGOING
        "finished" -> CatalogItemStatus.COMPLETED
        "on hiatus" -> CatalogItemStatus.ON_HIATUS
        "discontinued", "cancelled", "canceled" -> CatalogItemStatus.CANCELLED
        else -> CatalogItemStatus.UNKNOWN
    }

    private fun String.toCatalogFormat(): CatalogItemFormat = when (normalizedMalValue()) {
        "manga" -> CatalogItemFormat.MANGA
        "one shot" -> CatalogItemFormat.ONE_SHOT
        "manhwa" -> CatalogItemFormat.MANHWA
        "manhua" -> CatalogItemFormat.MANHUA
        "doujin", "doujinshi" -> CatalogItemFormat.DOUJIN
        "novel", "light novel" -> CatalogItemFormat.NOVEL
        else -> CatalogItemFormat.UNKNOWN
    }

    private fun String.normalizedMalValue(): String =
        lowercase().replace('_', ' ').trim().replace(WHITESPACE_REGEX, " ")

    private fun String.requireMalId(): Int =
        toIntOrNull() ?: throw IllegalArgumentException("Invalid MAL external id: $this")

    private suspend fun <T> captureResult(block: suspend () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    companion object {
        private const val MAL_SCORE_MAX = 10.0
        private val WHITESPACE_REGEX = Regex("""\s+""")

        internal fun forTest(api: MalIntegrationApi): MalIntegrationProvider =
            MalIntegrationProvider(api)
    }
}
