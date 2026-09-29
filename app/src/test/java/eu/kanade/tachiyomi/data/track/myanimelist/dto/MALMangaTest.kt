package eu.kanade.tachiyomi.data.track.myanimelist.dto

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MALMangaTest {

    @Test
    fun `manga metadata preserves genres and rating vote count`() {
        val track = MALManga(
            id = 42L,
            title = "Monster",
            synopsis = "A psychological thriller",
            numChapters = 162,
            numVolumes = 18,
            mean = 8.72,
            numScoringUsers = 143_215,
            covers = MALMangaCovers(large = "https://example.invalid/monster.jpg"),
            status = "finished",
            mediaType = "manga",
            startDate = "1994-12-05",
            endDate = "2001-12-20",
            authors = listOf(
                MALAuthorNode(
                    node = MALAuthor(id = 1, firstName = "Naoki", lastName = "Urasawa"),
                    role = "Story & Art",
                ),
            ),
            genres = listOf(
                MALGenre(id = 8, name = "Drama"),
                MALGenre(id = 7, name = "Mystery"),
            ),
        ).toTrackSearch(trackerId = 1L)

        track.score shouldBe 8.72
        track.score_votes shouldBe 143_215
        track.genres shouldContainExactly listOf("Drama", "Mystery")
        track.authors shouldContainExactly listOf("Naoki Urasawa")
        track.artists shouldContainExactly listOf("Naoki Urasawa")
    }
}
