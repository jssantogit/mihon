package eu.kanade.tachiyomi.data.tsuzuki.integration

import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.data.track.myanimelist.MalIntegrationApi
import eu.kanade.tachiyomi.data.track.myanimelist.MalUserListEntry
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.integration.UserListProvider
import tachiyomi.domain.tsuzuki.model.LibraryStatus

class MalUserLibraryProviderTest {

    @Test
    fun `mal projects account manga statuses into user library lists`() = runTest {
        val api = FakeMalIntegrationApi(
            entries = listOf(
                MalUserListEntry(
                    manga = malTrack(42, "Solo Leveling", "manhwa"),
                    status = "plan_to_read",
                    progress = 3.0,
                    score = 8.0,
                ),
            ),
        )
        val connected = MutableStateFlow(true)
        val provider = MalIntegrationProvider.forTest(api, connected)

        (provider as UserListProvider).connection.first() shouldBe true
        val snapshot = provider.fetchLibrary().getOrThrow()
        val entry = snapshot.entries.single()

        snapshot.lists.map { it.key } shouldContainExactly listOf(
            "mal:status:reading",
            "mal:status:plan_to_read",
            "mal:status:completed",
            "mal:status:on_hold",
            "mal:status:dropped",
        )
        entry.item.provider shouldBe "mal"
        entry.item.providerId shouldBe "42"
        entry.item.format shouldBe CatalogItemFormat.MANHWA
        entry.listKeys shouldBe setOf("mal:status:plan_to_read")
        entry.status shouldBe LibraryStatus.PLANNING
        entry.remoteStatus shouldBe "plan_to_read"
        entry.progress shouldBe 3.0
        entry.score shouldBe 8.0
    }

    private class FakeMalIntegrationApi(
        private val entries: List<MalUserListEntry>,
    ) : MalIntegrationApi {
        override suspend fun search(query: String): List<TrackSearch> = emptyList()
        override suspend fun getRanking(rankingType: String, offset: Int, limit: Int): List<TrackSearch> = emptyList()
        override suspend fun getMangaDetails(id: Int): TrackSearch = error("unused")
        override suspend fun getUserMangaList(): List<MalUserListEntry> = entries
    }

    private fun malTrack(id: Long, title: String, type: String): TrackSearch =
        TrackSearch.create(1L).apply {
            remote_id = id
            this.title = title
            publishing_type = type
            tracking_url = "https://myanimelist.net/manga/$id"
        }
}
