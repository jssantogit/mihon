package eu.kanade.tachiyomi.data.tsuzuki.integration

import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdatesIntegrationApi
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider

class MangaUpdatesIntegrationProviderTest {

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
