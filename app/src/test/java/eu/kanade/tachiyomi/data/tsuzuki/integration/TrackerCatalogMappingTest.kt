package eu.kanade.tachiyomi.data.tsuzuki.integration

import eu.kanade.tachiyomi.data.track.model.TrackSearch
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat

class TrackerCatalogMappingTest {

    @Test
    fun `tracker metadata mapping preserves creators and normalizes format`() {
        val track = TrackSearch.create(7L).apply {
            remote_id = 42L
            title = "Monster"
            authors = listOf("Naoki Urasawa", "", "Naoki Urasawa")
            artists = listOf("Naoki Urasawa")
            publishing_type = "Manga"
            tracking_url = "https://example.invalid/42"
        }

        val item = track.toIntegrationCatalogItem("mangaupdates")

        item.authors shouldContainExactly listOf("Naoki Urasawa")
        item.artists shouldContainExactly listOf("Naoki Urasawa")
        item.format shouldBe CatalogItemFormat.MANGA
    }
}
