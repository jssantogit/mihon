package eu.kanade.tachiyomi.data.tsuzuki.integration

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.bangumi.BangumiIntegrationApi
import eu.kanade.tachiyomi.data.track.bangumi.BangumiUserLibraryApi
import eu.kanade.tachiyomi.data.track.bangumi.BangumiUserListEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.UserListProvider
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
@ContributesIntoSet(AppScope::class, binding = binding<UserListProvider>())
class BangumiIntegrationProvider private constructor(
    private val api: BangumiIntegrationApi,
    private val userLibraryApi: BangumiUserLibraryApi,
    override val connection: Flow<Boolean>,
) : SearchProvider, DiscoveryProvider, MetadataProvider, UserListProvider {

    @Inject
    constructor(trackerManager: TrackerManager) : this(
        api = trackerManager.bangumi.integrationApi,
        userLibraryApi = trackerManager.bangumi.userLibraryApi,
        connection = trackerManager.bangumi.isLoggedInFlow,
    )

    override val integrationId = IntegrationId("bangumi")

    override suspend fun search(query: CatalogQuery): Result<CatalogPage> = capture {
        val text = query.query?.trim().orEmpty()
        if (text.isEmpty()) return@capture CatalogPage(emptyList(), false)

        val all = api.search(text)
        val page = all
            .drop(query.offset.coerceAtLeast(0))
            .take(query.limit.coerceAtLeast(0))

        CatalogPage(
            items = page.map { it.toIntegrationCatalogItem(integrationId.value) },
            hasNextPage = query.offset + page.size < all.size,
            totalCount = all.size,
        )
    }

    override suspend fun trending(offset: Int, limit: Int): Result<CatalogPage> =
        Result.success(CatalogPage(emptyList(), hasNextPage = false))

    override suspend fun popular(offset: Int, limit: Int): Result<CatalogPage> = capture {
        val items = api.browse(
            sort = BANGUMI_RANK_SORT,
            offset = offset,
            limit = limit,
        ).map { it.toIntegrationCatalogItem(integrationId.value) }

        CatalogPage(
            items = items,
            hasNextPage = limit > 0 && items.size == limit,
        )
    }

    override suspend fun recentlyUpdated(offset: Int, limit: Int): Result<CatalogPage> =
        Result.success(CatalogPage(emptyList(), hasNextPage = false))

    override suspend fun getDetails(externalId: String) = capture {
        api.getMangaDetails(externalId.requireBangumiId())
            .toIntegrationCatalogItem(integrationId.value)
    }

    override suspend fun fetchLibrary(): Result<UserLibrarySnapshot> = capture {
        UserLibrarySnapshot(
            lists = BANGUMI_USER_LISTS,
            entries = userLibraryApi.getUserLibrary().mapNotNull { it.toUserLibraryEntry() },
        )
    }

    private fun BangumiUserListEntry.toUserLibraryEntry(): UserLibraryEntry? {
        val remoteStatus = collectionType.toRemoteStatus() ?: return null
        return UserLibraryEntry(
            item = manga.toIntegrationCatalogItem(integrationId.value),
            listKeys = setOf("bangumi:status:$remoteStatus"),
            status = collectionType.toLibraryStatus(),
            remoteStatus = remoteStatus,
            progress = progress,
            score = score.takeIf { it > 0.0 },
            listedAt = updatedAt?.toEpochMillisOrNull(),
        )
    }

    private fun Int.toRemoteStatus(): String? = when (this) {
        BANGUMI_WISH -> "wish"
        BANGUMI_DONE -> "done"
        BANGUMI_DOING -> "doing"
        BANGUMI_ON_HOLD -> "on_hold"
        BANGUMI_DROPPED -> "dropped"
        else -> null
    }

    private fun Int.toLibraryStatus(): LibraryStatus? = when (this) {
        BANGUMI_WISH -> LibraryStatus.PLANNING
        BANGUMI_DONE -> LibraryStatus.COMPLETED
        BANGUMI_DOING -> LibraryStatus.READING
        BANGUMI_ON_HOLD -> LibraryStatus.ON_HOLD
        BANGUMI_DROPPED -> LibraryStatus.DROPPED
        else -> null
    }

    private fun String.toEpochMillisOrNull(): Long? = runCatching {
        Instant.parse(this).toEpochMilliseconds()
    }.getOrNull()

    private fun String.requireBangumiId(): Int =
        toIntOrNull() ?: throw IllegalArgumentException("Invalid Bangumi external id: $this")

    private suspend fun <T> capture(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

    companion object {
        private const val BANGUMI_RANK_SORT = "rank"
        private const val BANGUMI_WISH = 1
        private const val BANGUMI_DONE = 2
        private const val BANGUMI_DOING = 3
        private const val BANGUMI_ON_HOLD = 4
        private const val BANGUMI_DROPPED = 5
        private const val BANGUMI_STATUS_SELECTION_GROUP = "bangumi:status"

        private val BANGUMI_USER_LISTS = listOf(
            UserListDefinition(
                key = "bangumi:status:wish",
                title = "Plan to Read",
                status = LibraryStatus.PLANNING,
                selectionGroup = BANGUMI_STATUS_SELECTION_GROUP,
            ),
            UserListDefinition(
                key = "bangumi:status:done",
                title = "Completed",
                status = LibraryStatus.COMPLETED,
                selectionGroup = BANGUMI_STATUS_SELECTION_GROUP,
            ),
            UserListDefinition(
                key = "bangumi:status:doing",
                title = "Reading",
                status = LibraryStatus.READING,
                selectionGroup = BANGUMI_STATUS_SELECTION_GROUP,
            ),
            UserListDefinition(
                key = "bangumi:status:on_hold",
                title = "On Hold",
                status = LibraryStatus.ON_HOLD,
                selectionGroup = BANGUMI_STATUS_SELECTION_GROUP,
            ),
            UserListDefinition(
                key = "bangumi:status:dropped",
                title = "Dropped",
                status = LibraryStatus.DROPPED,
                selectionGroup = BANGUMI_STATUS_SELECTION_GROUP,
            ),
        )

        private object EmptyBangumiUserLibraryApi : BangumiUserLibraryApi {
            override suspend fun getUserLibrary(): List<BangumiUserListEntry> = emptyList()
        }

        internal fun forTest(
            api: BangumiIntegrationApi,
            userLibraryApi: BangumiUserLibraryApi = EmptyBangumiUserLibraryApi,
            connection: Flow<Boolean> = flowOf(true),
        ): BangumiIntegrationProvider = BangumiIntegrationProvider(
            api = api,
            userLibraryApi = userLibraryApi,
            connection = connection,
        )
    }
}
