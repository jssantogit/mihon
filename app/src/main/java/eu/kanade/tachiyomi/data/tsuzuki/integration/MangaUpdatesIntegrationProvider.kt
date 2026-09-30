package eu.kanade.tachiyomi.data.tsuzuki.integration

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdatesIntegrationApi
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdatesUserLibraryApi
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdatesUserLibrarySnapshot
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdatesUserList
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdatesUserListEntry
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
class MangaUpdatesIntegrationProvider private constructor(
    private val api: MangaUpdatesIntegrationApi,
    private val userLibraryApi: MangaUpdatesUserLibraryApi,
    override val connection: Flow<Boolean>,
) : SearchProvider, DiscoveryProvider, MetadataProvider, UserListProvider {

    @Inject
    constructor(trackerManager: TrackerManager) : this(
        api = trackerManager.mangaUpdates.integrationApi,
        userLibraryApi = trackerManager.mangaUpdates.userLibraryApi,
        connection = trackerManager.mangaUpdates.isLoggedInFlow,
    )

    override val integrationId = IntegrationId("mangaupdates")

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

    override suspend fun trending(offset: Int, limit: Int): Result<CatalogPage> = capture {
        val items = api.discover(
            orderBy = MANGA_UPDATES_WEEKLY_RANK,
            offset = offset,
            limit = limit,
        ).map { it.toIntegrationCatalogItem(integrationId.value) }

        CatalogPage(
            items = items,
            hasNextPage = limit > 0 && items.size == limit,
        )
    }

    override suspend fun popular(offset: Int, limit: Int): Result<CatalogPage> = capture {
        val items = api.discover(
            orderBy = MANGA_UPDATES_READING_RANK,
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
        api.getMangaDetails(externalId.requireMangaUpdatesId())
            .toIntegrationCatalogItem(integrationId.value)
    }

    override suspend fun fetchLibrary(): Result<UserLibrarySnapshot> = capture {
        val remote = userLibraryApi.getUserLibrary()
        val listsById = remote.lists.associateBy(MangaUpdatesUserList::id)

        UserLibrarySnapshot(
            lists = remote.lists.map(MangaUpdatesUserList::toUserListDefinition),
            entries = remote.entries.mapNotNull { entry ->
                entry.toUserLibraryEntry(listsById[entry.listId] ?: return@mapNotNull null)
            },
        )
    }

    private fun MangaUpdatesUserList.toUserListDefinition(): UserListDefinition {
        val normalizedType = type.normalizedListType()
        return UserListDefinition(
            key = listKey(id),
            title = title,
            status = normalizedType.toLibraryStatus(),
            selectionGroup = MANGA_UPDATES_LIST_SELECTION_GROUP,
        )
    }

    private fun MangaUpdatesUserListEntry.toUserLibraryEntry(
        list: MangaUpdatesUserList,
    ): UserLibraryEntry {
        val normalizedType = list.type.normalizedListType()
        return UserLibraryEntry(
            item = manga.toIntegrationCatalogItem(integrationId.value),
            listKeys = setOf(listKey(list.id)),
            status = normalizedType.toLibraryStatus(),
            remoteStatus = normalizedType,
            progress = progress,
            score = score.takeIf { it > 0.0 },
            listedAt = addedAt?.toEpochMillisOrNull(),
        )
    }

    private fun String.normalizedListType(): String =
        lowercase().trim()

    private fun String.toLibraryStatus(): LibraryStatus? = when (this) {
        "read" -> LibraryStatus.READING
        "wish" -> LibraryStatus.PLANNING
        "complete" -> LibraryStatus.COMPLETED
        "hold" -> LibraryStatus.ON_HOLD
        "unfinished" -> LibraryStatus.DROPPED
        else -> null
    }

    private fun String.toEpochMillisOrNull(): Long? = runCatching {
        Instant.parse(this).toEpochMilliseconds()
    }.getOrNull()

    private fun listKey(listId: Long): String = "mangaupdates:list:$listId"

    private fun String.requireMangaUpdatesId(): Long =
        toLongOrNull() ?: throw IllegalArgumentException("Invalid MangaUpdates external id: $this")

    private suspend fun <T> capture(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

    companion object {
        private const val MANGA_UPDATES_WEEKLY_RANK = "week_pos"
        private const val MANGA_UPDATES_READING_RANK = "list_reading"
        private const val MANGA_UPDATES_LIST_SELECTION_GROUP = "mangaupdates:list"

        private object EmptyMangaUpdatesUserLibraryApi : MangaUpdatesUserLibraryApi {
            override suspend fun getUserLibrary(): MangaUpdatesUserLibrarySnapshot =
                MangaUpdatesUserLibrarySnapshot(
                    lists = emptyList(),
                    entries = emptyList(),
                )
        }

        internal fun forTest(
            api: MangaUpdatesIntegrationApi,
            userLibraryApi: MangaUpdatesUserLibraryApi = EmptyMangaUpdatesUserLibraryApi,
            connection: Flow<Boolean> = flowOf(true),
        ): MangaUpdatesIntegrationProvider = MangaUpdatesIntegrationProvider(
            api = api,
            userLibraryApi = userLibraryApi,
            connection = connection,
        )
    }
}
