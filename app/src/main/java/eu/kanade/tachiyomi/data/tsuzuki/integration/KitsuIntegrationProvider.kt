package eu.kanade.tachiyomi.data.tsuzuki.integration

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.kitsu.KitsuUserLibraryApi
import eu.kanade.tachiyomi.data.track.kitsu.KitsuUserListEntry
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuManga
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import tachiyomi.data.tsuzuki.kitsu.KitsuCatalogProvider
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.model.CatalogScore
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.UserListProvider
import tachiyomi.domain.tsuzuki.integration.model.CatalogRatingMatch
import tachiyomi.domain.tsuzuki.integration.model.ExternalRating
import tachiyomi.domain.tsuzuki.integration.model.UserLibraryEntry
import tachiyomi.domain.tsuzuki.integration.model.UserLibrarySnapshot
import tachiyomi.domain.tsuzuki.integration.model.UserListDefinition
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * Exposes Kitsu's catalog capabilities without making it a chapter authority.
 *
 * The legacy [KitsuCatalogProvider] remains available for compatibility while remaining catalog
 * callers migrate to Integration capabilities.
 */
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<SearchProvider>())
@ContributesIntoSet(AppScope::class, binding = binding<DiscoveryProvider>())
@ContributesIntoSet(AppScope::class, binding = binding<MetadataProvider>())
@ContributesIntoSet(AppScope::class, binding = binding<RatingsProvider>())
@ContributesIntoSet(AppScope::class, binding = binding<UserListProvider>())
class KitsuIntegrationProvider private constructor(
    private val delegate: KitsuCatalogProvider,
    private val userLibraryApi: KitsuUserLibraryApi,
    override val connection: Flow<Boolean>,
) : SearchProvider, DiscoveryProvider, MetadataProvider, RatingsProvider, UserListProvider {

    @Inject
    constructor(
        delegate: KitsuCatalogProvider,
        trackerManager: TrackerManager,
    ) : this(
        delegate = delegate,
        userLibraryApi = trackerManager.kitsu.integrationApi,
        connection = trackerManager.kitsu.isLoggedInFlow,
    )

    internal constructor(
        delegate: KitsuCatalogProvider,
    ) : this(
        delegate = delegate,
        userLibraryApi = EmptyKitsuUserLibraryApi,
        connection = flowOf(false),
    )

    override val integrationId: IntegrationId = IntegrationId("kitsu")

    override suspend fun search(query: CatalogQuery) = delegate.search(query)

    override suspend fun trending(offset: Int, limit: Int): Result<CatalogPage> =
        delegate.getTrending(offset, limit)

    override suspend fun popular(offset: Int, limit: Int): Result<CatalogPage> =
        delegate.getPopular(offset, limit)

    override suspend fun recentlyUpdated(offset: Int, limit: Int): Result<CatalogPage> =
        delegate.search(
            CatalogQuery(
                sort = CatalogSort.UPDATED_DESC,
                offset = offset,
                limit = limit,
            ),
        )

    override suspend fun getDetails(externalId: String) = delegate.getDetails(externalId)

    override suspend fun ratings(externalId: String): Result<List<ExternalRating>> =
        delegate.getDetails(externalId).map { item ->
            listOfNotNull(
                item.score?.let { score ->
                    ExternalRating(
                        providerId = integrationId.value,
                        label = "Kitsu",
                        value = score.value,
                        scaleMax = score.maxValue,
                    )
                },
            )
        }

    override suspend fun ratingFor(item: CatalogItem): Result<CatalogRatingMatch?> {
        val knownKitsuId = when {
            item.provider == integrationId.value -> item.providerId
            else -> item.externalIds[integrationId.value]
        }?.takeIf(String::isNotBlank)

        if (knownKitsuId != null) {
            return ratings(knownKitsuId).map { values ->
                values.firstOrNull()?.let { rating ->
                    CatalogRatingMatch(
                        externalId = knownKitsuId,
                        rating = rating,
                    )
                }
            }
        }

        val malId = when {
            item.provider == "mal" -> item.providerId
            else -> item.externalIds["mal"]
        }?.takeIf(String::isNotBlank) ?: return Result.success(null)

        return delegate.search(
            CatalogQuery(
                query = item.title,
                limit = RATING_IDENTITY_SEARCH_LIMIT,
            ),
        ).map { page ->
            val exact = page.items.firstOrNull { candidate ->
                candidate.externalIds["mal"] == malId
            } ?: return@map null

            exact.score?.let { score ->
                CatalogRatingMatch(
                    externalId = exact.providerId,
                    rating = ExternalRating(
                        providerId = integrationId.value,
                        label = "Kitsu",
                        value = score.value,
                        scaleMax = score.maxValue,
                    ),
                )
            }
        }
    }

    override suspend fun fetchLibrary(): Result<UserLibrarySnapshot> = captureResult {
        UserLibrarySnapshot(
            lists = KITSU_USER_LISTS,
            entries = userLibraryApi.getUserMangaList().map { it.toUserLibraryEntry() },
        )
    }

    private fun KitsuUserListEntry.toUserLibraryEntry(): UserLibraryEntry {
        val normalizedStatus = status.lowercase().trim()
        return UserLibraryEntry(
            item = manga.toCatalogItem(),
            listKeys = setOf("kitsu:status:$normalizedStatus"),
            status = normalizedStatus.toLibraryStatus(),
            remoteStatus = normalizedStatus,
            progress = progress,
            score = score.takeIf { it > 0.0 },
            listedAt = updatedAt?.toEpochMillisOrNull(),
        )
    }

    private fun KitsuManga.toCatalogItem(): CatalogItem {
        return CatalogItem(
            provider = integrationId.value,
            providerId = id,
            title = titles.preferred,
            synopsis = description["en"]?.takeIf(String::isNotBlank),
            coverUrl = posterImage.getPosterUrl().takeIf(String::isNotBlank),
            status = status.toCatalogStatus(),
            format = subtype.toCatalogFormat(),
            score = averageRating?.let { value ->
                CatalogScore(
                    provider = integrationId.value,
                    value = value,
                    maxValue = KITSU_SCORE_MAX,
                )
            },
            authors = staff.nodes
                .filter { it.role.contains("Story", ignoreCase = true) }
                .map { it.person.name }
                .filter(String::isNotBlank)
                .distinct(),
            artists = staff.nodes
                .filter { it.role.contains("Art", ignoreCase = true) }
                .map { it.person.name }
                .filter(String::isNotBlank)
                .distinct(),
            startDate = startDate,
            endDate = endDate,
            chapterCount = chapterCount?.takeIf { it > 0 && it <= Int.MAX_VALUE }?.toInt(),
        )
    }

    private fun String.toLibraryStatus(): LibraryStatus? = when (this) {
        "current" -> LibraryStatus.READING
        "planned" -> LibraryStatus.PLANNING
        "completed" -> LibraryStatus.COMPLETED
        "on_hold" -> LibraryStatus.ON_HOLD
        "dropped" -> LibraryStatus.DROPPED
        else -> null
    }

    private fun String.toCatalogStatus(): CatalogItemStatus = when (lowercase().trim()) {
        "current" -> CatalogItemStatus.ONGOING
        "finished" -> CatalogItemStatus.COMPLETED
        else -> CatalogItemStatus.UNKNOWN
    }

    private fun String.toCatalogFormat(): CatalogItemFormat = when (lowercase().trim()) {
        "manga" -> CatalogItemFormat.MANGA
        "novel" -> CatalogItemFormat.NOVEL
        "oneshot", "one_shot", "one shot" -> CatalogItemFormat.ONE_SHOT
        "manhwa" -> CatalogItemFormat.MANHWA
        "manhua" -> CatalogItemFormat.MANHUA
        "doujin", "doujinshi" -> CatalogItemFormat.DOUJIN
        else -> CatalogItemFormat.UNKNOWN
    }

    private fun String.toEpochMillisOrNull(): Long? = runCatching {
        Instant.parse(this).toEpochMilliseconds()
    }.getOrNull()

    private suspend fun <T> captureResult(block: suspend () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    companion object {
        private const val KITSU_SCORE_MAX = 100.0
        private const val RATING_IDENTITY_SEARCH_LIMIT = 10
        private const val KITSU_STATUS_SELECTION_GROUP = "kitsu:status"

        private val KITSU_USER_LISTS = listOf(
            UserListDefinition(
                key = "kitsu:status:current",
                title = "Reading",
                status = LibraryStatus.READING,
                selectionGroup = KITSU_STATUS_SELECTION_GROUP,
            ),
            UserListDefinition(
                key = "kitsu:status:planned",
                title = "Plan to Read",
                status = LibraryStatus.PLANNING,
                selectionGroup = KITSU_STATUS_SELECTION_GROUP,
            ),
            UserListDefinition(
                key = "kitsu:status:completed",
                title = "Completed",
                status = LibraryStatus.COMPLETED,
                selectionGroup = KITSU_STATUS_SELECTION_GROUP,
            ),
            UserListDefinition(
                key = "kitsu:status:on_hold",
                title = "On Hold",
                status = LibraryStatus.ON_HOLD,
                selectionGroup = KITSU_STATUS_SELECTION_GROUP,
            ),
            UserListDefinition(
                key = "kitsu:status:dropped",
                title = "Dropped",
                status = LibraryStatus.DROPPED,
                selectionGroup = KITSU_STATUS_SELECTION_GROUP,
            ),
        )

        private object EmptyKitsuUserLibraryApi : KitsuUserLibraryApi {
            override suspend fun getUserMangaList(): List<KitsuUserListEntry> = emptyList()
        }

        internal fun forTest(
            delegate: KitsuCatalogProvider,
            userLibraryApi: KitsuUserLibraryApi,
            connection: Flow<Boolean> = flowOf(true),
        ): KitsuIntegrationProvider = KitsuIntegrationProvider(
            delegate = delegate,
            userLibraryApi = userLibraryApi,
            connection = connection,
        )
    }
}
