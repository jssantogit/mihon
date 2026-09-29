package eu.kanade.tachiyomi.data.tsuzuki.integration

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdatesIntegrationApi
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import kotlin.coroutines.cancellation.CancellationException

@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<SearchProvider>())
@ContributesIntoSet(AppScope::class, binding = binding<DiscoveryProvider>())
@ContributesIntoSet(AppScope::class, binding = binding<MetadataProvider>())
class MangaUpdatesIntegrationProvider private constructor(
    private val api: MangaUpdatesIntegrationApi,
) : SearchProvider, DiscoveryProvider, MetadataProvider {

    @Inject
    constructor(trackerManager: TrackerManager) : this(trackerManager.mangaUpdates.integrationApi)
    override val integrationId = IntegrationId("mangaupdates")

    override suspend fun search(query: CatalogQuery): Result<CatalogPage> = capture {
        val text = query.query?.trim().orEmpty()
        if (text.isEmpty()) return@capture CatalogPage(emptyList(), false)
        val all = api.search(text)
        val page = all.drop(query.offset.coerceAtLeast(0)).take(query.limit.coerceAtLeast(0))
        CatalogPage(
            items = page.map { it.toIntegrationCatalogItem(integrationId.value) },
            hasNextPage = query.offset + page.size < all.size,
            totalCount = all.size,
        )
        companion object {
        private const val MANGA_UPDATES_WEEKLY_RANK = "week_pos"
        private const val MANGA_UPDATES_READING_RANK = "list_reading"

        internal fun forTest(api: MangaUpdatesIntegrationApi): MangaUpdatesIntegrationProvider =
            MangaUpdatesIntegrationProvider(api)
    }
}

    override suspend fun trending(offset: Int, limit: Int): Result<CatalogPage> = capture {
        val items = api.discover(
            orderBy = MANGA_UPDATES_WEEKLY_RANK,
            offset = offset,
            limit = limit,
        ).map { it.toIntegrationCatalogItem(integrationId.value) }
        CatalogPage(items = items, hasNextPage = limit > 0 && items.size == limit)
    }

    override suspend fun popular(offset: Int, limit: Int): Result<CatalogPage> = capture {
        val items = api.discover(
            orderBy = MANGA_UPDATES_READING_RANK,
            offset = offset,
            limit = limit,
        ).map { it.toIntegrationCatalogItem(integrationId.value) }
        CatalogPage(items = items, hasNextPage = limit > 0 && items.size == limit)
    }

    override suspend fun recentlyUpdated(offset: Int, limit: Int): Result<CatalogPage> =
        Result.success(CatalogPage(emptyList(), hasNextPage = false))

    override suspend fun getDetails(externalId: String) = capture {
        api.getMangaDetails(externalId.requireMangaUpdatesId())
            .toIntegrationCatalogItem(integrationId.value)
    }

    private fun String.requireMangaUpdatesId(): Long =
        toLongOrNull() ?: throw IllegalArgumentException("Invalid MangaUpdates external id: $this")

    private suspend fun <T> capture(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
}
