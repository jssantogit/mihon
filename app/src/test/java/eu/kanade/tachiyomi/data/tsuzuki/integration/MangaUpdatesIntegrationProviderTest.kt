package eu.kanade.tachiyomi.data.tsuzuki.integration

import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdatesIntegrationApi
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdatesUserLibraryApi
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdatesUserLibrarySnapshot
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdatesUserList
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdatesUserListEntry
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.UserListProvider
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import kotlin.time.Instant

class MangaUpdatesIntegrationProviderTest {

    @Test
    fun `mangaupdates exposes account lists capability`() = runTest {
        val provider = MangaUpdatesIntegrationProvider.forTest(FakeMangaUpdatesIntegrationApi())

        (provider as Any is UserListProvider) shouldBe true
    }

    @Test
    fun `mangaupdates preserves provider lists and projects remote status into unified library`() = runTest {
        val addedAt = "2026-09-30T08:00:00Z"
        val provider = MangaUpdatesIntegrationProvider.forTest(
            api = FakeMangaUpdatesIntegrationApi(),
            userLibraryApi = FakeMangaUpdatesUserLibraryApi(
                MangaUpdatesUserLibrarySnapshot(
                    lists = listOf(
                        MangaUpdatesUserList(
                            id = 1,
                            title = "Wish List",
                            type = "wish",
                            custom = false,
                        ),
                        MangaUpdatesUserList(
                            id = 3,
                            title = "Unfinished List",
                            type = "unfinished",
                            custom = false,
                        ),
                        MangaUpdatesUserList(
                            id = 12,
                            title = "Favorites",
                            type = "read",
                            custom = true,
                        ),
                    ),
                    entries = listOf(
                        MangaUpdatesUserListEntry(
                            manga = track(42, "Solo Leveling", 9.1).apply {
                                publishing_type = "Manhwa"
                            },
                            listId = 12,
                            progress = 77.0,
                            score = 9.0,
                            addedAt = addedAt,
                        ),
                    ),
                ),
            ),
        )

        val snapshot = (provider as UserListProvider).fetchLibrary().getOrThrow()

        snapshot.lists.map { it.key } shouldBe listOf(
            "mangaupdates:list:1",
            "mangaupdates:list:3",
            "mangaupdates:list:12",
        )
        snapshot.lists.map { it.title } shouldBe listOf(
            "Wish List",
            "Unfinished List",
            "Favorites",
        )
        snapshot.lists.map { it.status } shouldBe listOf(
            LibraryStatus.PLANNING,
            LibraryStatus.DROPPED,
            LibraryStatus.READING,
        )
        snapshot.lists.map { it.selectionGroup } shouldBe listOf(
            "mangaupdates:status",
            "mangaupdates:status",
            "mangaupdates:list",
        )

        val entry = snapshot.entries.single()
        entry.item.provider shouldBe "mangaupdates"
        entry.item.providerId shouldBe "42"
        entry.item.format shouldBe CatalogItemFormat.MANHWA
        entry.listKeys shouldBe setOf("mangaupdates:list:12")
        entry.status shouldBe LibraryStatus.READING
        entry.remoteStatus shouldBe "read"
        entry.progress shouldBe 77.0
        entry.score shouldBe 9.0
        entry.listedAt shouldBe Instant.parse(addedAt).toEpochMilliseconds()
    }

    @Test
    fun `mangaupdates discovery uses weekly and reading-list rankings`() = runTest {
        val api = FakeMangaUpdatesIntegrationApi(
            discoveryResults = listOf(track(42, "Monster", 9.12)),
        )
        val provider = MangaUpdatesIntegrationProvider.forTest(api)

        (provider as Any is DiscoveryProvider) shouldBe true

        val trending = (provider as DiscoveryProvider).trending(0, 20).getOrThrow()
        val popular = provider.popular(20, 20).getOrThrow()
        val recent = provider.recentlyUpdated(0, 20).getOrThrow()

        trending.items.single().provider shouldBe "mangaupdates"
        trending.items.single().title shouldBe "Monster"
        popular.items.single().providerId shouldBe "42"
        recent.items shouldBe emptyList()

        api.discoveryRequests shouldContainExactly listOf(
            Triple("week_pos", 0, 20),
            Triple("list_reading", 20, 20),
        )
    }

    private class FakeMangaUpdatesUserLibraryApi(
        private val snapshot: MangaUpdatesUserLibrarySnapshot,
    ) : MangaUpdatesUserLibraryApi {
        override suspend fun getUserLibrary(): MangaUpdatesUserLibrarySnapshot = snapshot
    }

    private class FakeMangaUpdatesIntegrationApi(
        private val discoveryResults: List<TrackSearch> = emptyList(),
    ) : MangaUpdatesIntegrationApi {
        val discoveryRequests = mutableListOf<Triple<String, Int, Int>>()

        override suspend fun search(query: String): List<TrackSearch> = emptyList()

        override suspend fun discover(
            orderBy: String,
            offset: Int,
            limit: Int,
        ): List<TrackSearch> {
            discoveryRequests += Triple(orderBy, offset, limit)
            return discoveryResults
        }

        override suspend fun getMangaDetails(id: Long): TrackSearch =
            error("Not used")
    }

    private fun track(id: Long, title: String, score: Double): TrackSearch =
        TrackSearch.create(7L).apply {
            remote_id = id
            this.title = title
            this.score = score
            score_votes = 500
        }
}
