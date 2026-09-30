package eu.kanade.tachiyomi.data.tsuzuki.integration

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.data.track.myanimelist.MalIntegrationApi
import eu.kanade.tachiyomi.data.track.myanimelist.MalUserListEntry
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.model.CatalogScore
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.UserListProvider
import tachiyomi.domain.tsuzuki.integration.model.ExternalRating
import tachiyomi.domain.tsuzuki.integration.model.UserLibraryEntry
import tachiyomi.domain.tsuzuki.integration.model.UserLibrarySnapshot
import tachiyomi.domain.tsuzuki.integration.model.UserListDefinition
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<SearchProvider>())
@ContributesIntoSet(AppScope::class, binding = binding<DiscoveryProvider>())
@ContributesIntoSet(AppScope::class, binding = binding<MetadataProvider>())
@ContributesIntoSet(AppScope::class, binding = binding<RatingsProvider>())
@ContributesIntoSet(AppScope::class, binding = binding<UserListProvider>())
class MalIntegrationProvider private constructor(
    private val api: MalIntegrationApi,
    override val connection: Flow<Boolean>,
) : SearchProvider, DiscoveryProvider, MetadataProvider, RatingsProvider, UserListProvider {

    @Inject
    constructor(trackerManager: TrackerManager) : this(
        api = trackerManager.myAnimeList.integrationApi,
        connection = trackerManager.myAnimeList.isLoggedInFlow,
    )

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

    override suspend fun trending(offset: Int, limit: Int): Result<CatalogPage> =
        Result.success(CatalogPage(items = emptyList(), hasNextPage = false))

    override suspend fun popular(offset: Int, limit: Int): Result<CatalogPage> {
        return captureResult {
            val items = api.getRanking(
                rankingType = MAL_RANKING_BY_POPULARITY,
                offset = offset,
                limit = limit,
            ).map { it.toCatalogItem() }

            CatalogPage(
                items = items,
                hasNextPage = limit > 0 && items.size == limit,
            )
        }
    }

    override suspend fun recentlyUpdated(offset: Int, limit: Int): Result<CatalogPage> =
        Result.success(CatalogPage(items = emptyList(), hasNextPage = false))

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

    override suspend fun fetchLibrary(): Result<UserLibrarySnapshot> = captureResult {
        UserLibrarySnapshot(
            lists = MAL_USER_LISTS,
            entries = api.getUserMangaList().map { it.toUserLibraryEntry() },
        )
    }

    private fun MalUserListEntry.toUserLibraryEntry(): UserLibraryEntry {
        val normalizedStatus = status.lowercase().trim()
        return UserLibraryEntry(
            item = manga.toCatalogItem(),
            listKeys = setOf("mal:status:$normalizedStatus"),
            status = normalizedStatus.toLibraryStatus(),
            remoteStatus = normalizedStatus,
            progress = progress,
            score = score.takeIf { it > 0.0 },
            listedAt = updatedAt?.toEpochMillisOrNull(),
        )
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
                        voteCount = score_votes,
                    )
                },
            authors = authors.filter(String::isNotBlank).distinct(),
            artists = artists.filter(String::isNotBlank).distinct(),
            genres = genres.filter(String::isNotBlank).distinct(),
            startDate = start_date.ifBlank { null },
            endDate = end_date.ifBlank { null },
            chapterCount = total_chapters
                .takeIf { it > 0 && it <= Int.MAX_VALUE }
                ?.toInt(),
            volumeCount = total_volumes
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

    private fun String.toLibraryStatus(): LibraryStatus? = when (this) {
        "reading" -> LibraryStatus.READING
        "plan_to_read" -> LibraryStatus.PLANNING
        "completed" -> LibraryStatus.COMPLETED
        "on_hold" -> LibraryStatus.ON_HOLD
        "dropped" -> LibraryStatus.DROPPED
        else -> null
    }

    private fun String.toEpochMillisOrNull(): Long? = runCatching {
        Instant.parse(this).toEpochMilliseconds()
    }.getOrNull()

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
        private const val MAL_RANKING_BY_POPULARITY = "bypopularity"
        private const val MAL_STATUS_SELECTION_GROUP = "mal:status"
        private val WHITESPACE_REGEX = Regex("""\s+""")
        private val MAL_USER_LISTS = listOf(
            UserListDefinition(
                key = "mal:status:reading",
                title = "Reading",
                status = LibraryStatus.READING,
                selectionGroup = MAL_STATUS_SELECTION_GROUP,
            ),
            UserListDefinition(
                key = "mal:status:plan_to_read",
                title = "Plan to Read",
                status = LibraryStatus.PLANNING,
                selectionGroup = MAL_STATUS_SELECTION_GROUP,
            ),
            UserListDefinition(
                key = "mal:status:completed",
                title = "Completed",
                status = LibraryStatus.COMPLETED,
                selectionGroup = MAL_STATUS_SELECTION_GROUP,
            ),
            UserListDefinition(
                key = "mal:status:on_hold",
                title = "On Hold",
                status = LibraryStatus.ON_HOLD,
                selectionGroup = MAL_STATUS_SELECTION_GROUP,
            ),
            UserListDefinition(
                key = "mal:status:dropped",
                title = "Dropped",
                status = LibraryStatus.DROPPED,
                selectionGroup = MAL_STATUS_SELECTION_GROUP,
            ),
        )

        internal fun forTest(
            api: MalIntegrationApi,
            connection: Flow<Boolean> = flowOf(true),
        ): MalIntegrationProvider = MalIntegrationProvider(api, connection)
    }
}
