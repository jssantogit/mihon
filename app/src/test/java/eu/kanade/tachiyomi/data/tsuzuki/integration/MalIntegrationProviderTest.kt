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
                    publishingStatus = "finished",
                    publishingType = "manga",
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
        item.chapterCount shouldBe 162
        item.status shouldBe CatalogItemStatus.COMPLETED
        item.format shouldBe CatalogItemFormat.MANGA
        ChapterEvidenceProvider::class.java.isAssignableFrom(provider.javaClass) shouldBe false
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
        val api = FakeMalIntegrationApi(
            searchResults = listOf(
                malTrack(id = 7, title = "Ongoing", score = -1.0, chapters = 0),
            ),
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
        private val detailsById: Map<Int, TrackSearch> = emptyMap(),
    ) : MalIntegrationApi {

        val detailRequests = mutableListOf<Int>()

        override suspend fun search(query: String): List<TrackSearch> = searchResults

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
        synopsis: String = "",
        coverUrl: String = "",
        publishingStatus: String = "",
        publishingType: String = "",
    ): TrackSearch = TrackSearch.create(1L).apply {
        remote_id = id
        this.title = title
        this.score = score
        total_chapters = chapters
        summary = synopsis
        cover_url = coverUrl
        publishing_status = publishingStatus
        publishing_type = publishingType
        tracking_url = "https://myanimelist.net/manga/$id"
    }
}
