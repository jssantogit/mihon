package eu.kanade.tachiyomi.data.tsuzuki.integration

import eu.kanade.tachiyomi.data.track.bangumi.BangumiIntegrationApi
import eu.kanade.tachiyomi.data.track.bangumi.BangumiUserLibraryApi
import eu.kanade.tachiyomi.data.track.bangumi.BangumiUserListEntry
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

class BangumiIntegrationProviderTest {

    @Test
    fun `bangumi exposes account collection capability`() = runTest {
        val provider = BangumiIntegrationProvider.forTest(FakeBangumiIntegrationApi())

        (provider as Any is UserListProvider) shouldBe true
    }

    @Test
    fun `bangumi projects account collection statuses into unified library`() = runTest {
        val updatedAt = "2026-09-30T08:00:00+08:00"
        val manga = track(101, "Vagabond", 9.1).apply {
            publishing_type = "Manga"
        }
        val provider = BangumiIntegrationProvider.forTest(
            api = FakeBangumiIntegrationApi(),
            userLibraryApi = FakeBangumiUserLibraryApi(
                listOf(
                    BangumiUserListEntry(
                        manga = manga,
                        collectionType = 3,
                        progress = 77.0,
                        score = 9.0,
                        updatedAt = updatedAt,
                    ),
                ),
            ),
        )

        val snapshot = (provider as UserListProvider).fetchLibrary().getOrThrow()

        snapshot.lists.map { it.key } shouldBe listOf(
            "bangumi:status:wish",
            "bangumi:status:done",
            "bangumi:status:doing",
            "bangumi:status:on_hold",
            "bangumi:status:dropped",
        )

        val entry = snapshot.entries.single()
        entry.item.provider shouldBe "bangumi"
        entry.item.providerId shouldBe "101"
        entry.item.format shouldBe CatalogItemFormat.MANGA
        entry.listKeys shouldBe setOf("bangumi:status:doing")
        entry.status shouldBe LibraryStatus.READING
        entry.remoteStatus shouldBe "doing"
        entry.progress shouldBe 77.0
        entry.score shouldBe 9.0
        entry.listedAt shouldBe Instant.parse(updatedAt).toEpochMilliseconds()
    }

    @Test
    fun `bangumi discovery uses ranked manga browse`() = runTest {
        val api = FakeBangumiIntegrationApi(
            browseResults = listOf(track(99, "Berserk", 8.9)),
        )
        val provider = BangumiIntegrationProvider.forTest(api)

        (provider as Any is DiscoveryProvider) shouldBe true

        val popular = (provider as DiscoveryProvider).popular(0, 20).getOrThrow()
        val trending = provider.trending(0, 20).getOrThrow()
        val recent = provider.recentlyUpdated(0, 20).getOrThrow()

        popular.items.single().provider shouldBe "bangumi"
        popular.items.single().title shouldBe "Berserk"
        trending.items shouldBe emptyList()
        recent.items shouldBe emptyList()

        api.browseRequests shouldContainExactly listOf(Triple("rank", 0, 20))
    }

    private class FakeBangumiUserLibraryApi(
        private val entries: List<BangumiUserListEntry>,
    ) : BangumiUserLibraryApi {
        override suspend fun getUserLibrary(): List<BangumiUserListEntry> = entries
    }

    private class FakeBangumiIntegrationApi(
        private val browseResults: List<TrackSearch> = emptyList(),
    ) : BangumiIntegrationApi {
        val browseRequests = mutableListOf<Triple<String, Int, Int>>()

        override suspend fun search(query: String): List<TrackSearch> = emptyList()

        override suspend fun browse(
            sort: String,
            offset: Int,
            limit: Int,
        ): List<TrackSearch> {
            browseRequests += Triple(sort, offset, limit)
            return browseResults
        }

        override suspend fun getMangaDetails(id: Int): TrackSearch =
            error("Not used")
    }

    private fun track(id: Long, title: String, score: Double): TrackSearch =
        TrackSearch.create(5L).apply {
            remote_id = id
            this.title = title
            this.score = score
            score_votes = 300
        }
}
