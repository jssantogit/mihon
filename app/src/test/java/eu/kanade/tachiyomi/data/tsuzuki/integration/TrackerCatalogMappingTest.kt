package eu.kanade.tachiyomi.data.tsuzuki.integration

import eu.kanade.tachiyomi.data.track.model.TrackSearch
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus

class TrackerCatalogMappingTest {

    @Test
    fun `tracker metadata mapping preserves creators and normalizes format`() {
        val track = TrackSearch.create(7L).apply {
            remote_id = 42L
            title = "Monster"
            authors = listOf("Naoki Urasawa", "", "Naoki Urasawa")
            artists = listOf("Naoki Urasawa")
            publishing_type = "Manga"
            publishing_status = "18 Volumes (Complete)"
            genres = listOf("Drama", "", "Mystery")
            tags = listOf("Psychological", "", "Crime")
            score = 9.12
            score_votes = 12345
            total_volumes = 18
            start_date = "1994"
            end_date = "2001"
            tracking_url = "https://example.invalid/42"
        }

        val item = track.toIntegrationCatalogItem("mangaupdates")

        item.authors shouldContainExactly listOf("Naoki Urasawa")
        item.artists shouldContainExactly listOf("Naoki Urasawa")
        item.format shouldBe CatalogItemFormat.MANGA
        item.status shouldBe CatalogItemStatus.COMPLETED
        item.genres shouldContainExactly listOf("Drama", "Mystery")
        item.tags shouldContainExactly listOf("Psychological", "Crime")
        item.score?.voteCount shouldBe 12345
        item.volumeCount shouldBe 18
        item.startDate shouldBe "1994"
        item.endDate shouldBe "2001"
    }
}
