package eu.kanade.tachiyomi.data.tsuzuki.integration

import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.data.track.myanimelist.MalIntegrationApi
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider

class MalIntegrationProviderTest {

    @Test
    fun `mal search preserves external identity score provenance and chapter count metadata`() = runTest {
        val api = FakeMalIntegrationApi(
            searchResults = listOf(
                malTrack(
                    id = 42,
                    title = "Monster",
                    score = 8.72,
                    chapters = 162,
                    volumes = 18,
                    startDate = "1994-12-05",
                    endDate = "2001-12-20",
                    publishingStatus = "finished",
                    publishingType = "manga",
                    authors = listOf("Naoki Urasawa"),
                    artists = listOf("Naoki Urasawa"),
                    genres = listOf("Drama", "Mystery"),
                    scoreVotes = 143_215,
                ),
            ),
        )
        val provider = MalIntegrationProvider.forTest(api)

        val item = provider.search(CatalogQuery(query = "Monster")).getOrThrow().items.single()

        item.provider shouldBe "mal"
        item.providerId shouldBe "42"
        item.title shouldBe "Monster"
        item.score?.provider shouldBe "mal"
        item.score?.value shouldBe 8.72
        item.score?.maxValue shouldBe 10.0
        item.score?.voteCount shouldBe 143_215
        item.chapterCount shouldBe 162
        item.volumeCount shouldBe 18
        item.startDate shouldBe "1994-12-05"
        item.endDate shouldBe "2001-12-20"
        item.status shouldBe CatalogItemStatus.COMPLETED
        item.format shouldBe CatalogItemFormat.MANGA
        item.authors shouldContainExactly listOf("Naoki Urasawa")
        item.artists shouldContainExactly listOf("Naoki Urasawa")
        item.genres shouldContainExactly listOf("Drama", "Mystery")
        ChapterEvidenceProvider::class.java.isAssignableFrom(provider.javaClass) shouldBe false
    }

    @Test
    fun `mal participates in catalog discovery`() = runTest {
        val api = FakeMalIntegrationApi(
            rankingResults = listOf(
                malTrack(id = 42, title = "Monster", score = 8.72),
            ),
        )
        val provider = MalIntegrationProvider.forTest(api)

        (provider as Any is DiscoveryProvider) shouldBe true
        val page = (provider as DiscoveryProvider).popular(offset = 0, limit = 20).getOrThrow()

        page.items.single().provider shouldBe "mal"
        page.items.single().title shouldBe "Monster"
        api.rankingRequests shouldContainExactly listOf(Triple("bypopularity", 0, 20))
    }

    @Test
    fun `mal ratings retain MAL provenance without averaging`() = runTest {
        val api = FakeMalIntegrationApi(
            detailsById = mapOf(
                42 to malTrack(id = 42, title = "Monster", score = 8.72),
            ),
        )
        val provider = MalIntegrationProvider.forTest(api)

        val ratings = provider.ratings("42").getOrThrow()

        ratings.map { it.providerId } shouldContainExactly listOf("mal")
        ratings.map { it.label } shouldContainExactly listOf("MAL")
        ratings.map { it.value } shouldContainExactly listOf(8.72)
        ratings.map { it.scaleMax } shouldContainExactly listOf(10.0)
    }

    @Test
    fun `mal unknown chapter count and score stay absent instead of becoming synthetic data`() = runTest {
        val track = malTrack(id = 7, title = "Ongoing", score = -1.0, chapters = 0)
        val api = FakeMalIntegrationApi(
            searchResults = listOf(track),
            detailsById = mapOf(7 to track),
        )
        val provider = MalIntegrationProvider.forTest(api)

        val item = provider.search(CatalogQuery(query = "Ongoing")).getOrThrow().items.single()
        val ratings = provider.ratings("7").getOrThrow()

        item.chapterCount shouldBe null
        item.score shouldBe null
        ratings shouldBe emptyList()
    }

    @Test
    fun `mal metadata lookup uses the MAL external id`() = runTest {
        val api = FakeMalIntegrationApi(
            detailsById = mapOf(
                99 to malTrack(
                    id = 99,
                    title = "Berserk",
                    synopsis = "A dark fantasy manga",
                    coverUrl = "https://example.invalid/berserk.jpg",
                ),
            ),
        )
        val provider = MalIntegrationProvider.forTest(api)

        val item = provider.getDetails("99").getOrThrow()

        item.provider shouldBe "mal"
        item.providerId shouldBe "99"
        item.title shouldBe "Berserk"
        item.synopsis shouldBe "A dark fantasy manga"
        item.coverUrl shouldBe "https://example.invalid/berserk.jpg"
        api.detailRequests shouldContainExactly listOf(99)
    }

    private class FakeMalIntegrationApi(
        private val searchResults: List<TrackSearch> = emptyList(),
        private val rankingResults: List<TrackSearch> = emptyList(),
        private val detailsById: Map<Int, TrackSearch> = emptyMap(),
    ) : MalIntegrationApi {

        val detailRequests = mutableListOf<Int>()
        val rankingRequests = mutableListOf<Triple<String, Int, Int>>()

        override suspend fun search(query: String): List<TrackSearch> = searchResults

        override suspend fun getRanking(
            rankingType: String,
            offset: Int,
            limit: Int,
        ): List<TrackSearch> {
            rankingRequests += Triple(rankingType, offset, limit)
            return rankingResults
        }

        override suspend fun getMangaDetails(id: Int): TrackSearch {
            detailRequests += id
            return detailsById[id] ?: error("Missing MAL fixture for id=$id")
        }
    }

    private fun malTrack(
        id: Long,
        title: String,
        score: Double = -1.0,
        chapters: Long = 0,
        volumes: Long = 0,
        startDate: String = "",
        endDate: String = "",
        synopsis: String = "",
        coverUrl: String = "",
        publishingStatus: String = "",
        publishingType: String = "",
        authors: List<String> = emptyList(),
        artists: List<String> = emptyList(),
        genres: List<String> = emptyList(),
        scoreVotes: Int? = null,
    ): TrackSearch = TrackSearch.create(1L).apply {
        remote_id = id
        this.title = title
        this.score = score
        total_chapters = chapters
        total_volumes = volumes
        start_date = startDate
        end_date = endDate
        summary = synopsis
        cover_url = coverUrl
        publishing_status = publishingStatus
        publishing_type = publishingType
        this.authors = authors
        this.artists = artists
        this.genres = genres
        score_votes = scoreVotes
        tracking_url = "https://myanimelist.net/manga/$id"
    }
}
