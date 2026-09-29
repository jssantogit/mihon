package eu.kanade.tachiyomi.data.track.mangaupdates.dto

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MURecordTest {

    @Test
    fun `series details preserve taxonomy status creators and rating votes`() {
        val track = MURecord(
            seriesId = 42L,
            title = "Monster",
            type = "Manga",
            year = "1994",
            bayesianRating = 9.12,
            ratingVotes = 12345,
            genres = listOf(
                MUGenre("Drama"),
                MUGenre("Mystery"),
            ),
            categories = listOf(
                MUCategory("Psychological", votes = 50),
                MUCategory("Crime", votes = 25),
            ),
            status = "18 Volumes (Complete)",
            completed = true,
            authors = listOf(
                MUAuthor(name = "Naoki Urasawa", type = "Author"),
                MUAuthor(name = "Naoki Urasawa", type = "Artist"),
            ),
        ).toTrackSearch(id = 7L)

        track.publishing_status shouldBe "Finished"
        track.publishing_type shouldBe "Manga"
        track.start_date shouldBe "1994"
        track.score shouldBe 9.12
        track.score_votes shouldBe 12345
        track.genres shouldContainExactly listOf("Drama", "Mystery")
        track.tags shouldContainExactly listOf("Psychological", "Crime")
        track.authors shouldContainExactly listOf("Naoki Urasawa")
        track.artists shouldContainExactly listOf("Naoki Urasawa")
    }

    @Test
    fun `missing publication year stays blank`() {
        val track = MURecord(
            seriesId = 1L,
            title = "Unknown year",
        ).toTrackSearch(id = 7L)

        track.start_date shouldBe ""
    }
}
