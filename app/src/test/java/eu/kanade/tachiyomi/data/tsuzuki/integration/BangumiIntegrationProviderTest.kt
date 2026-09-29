package eu.kanade.tachiyomi.data.tsuzuki.integration

import eu.kanade.tachiyomi.data.track.bangumi.BangumiIntegrationApi
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider

class BangumiIntegrationProviderTest {

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
