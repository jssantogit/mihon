package eu.kanade.tachiyomi.data.track.bangumi.dto

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BGMSearchTest {

    @Test
    fun `Bangumi manga details preserve editorial counts and format`() {
        val track = BGMSubject(
            id = 42L,
            nameCn = "",
            name = "Example",
            summary = null,
            date = "2020-01-01",
            images = null,
            volumes = 18L,
            eps = 162L,
            rating = BGMSubjectRating(score = 8.7, total = 2400),
            tags = listOf(
                BGMSubjectTag("悬疑", 1200),
                BGMSubjectTag("漫画", 900),
            ),
            platform = "漫画",
        ).toTrackSearch(trackerId = 5L)

        track.total_chapters shouldBe 162L
        track.total_volumes shouldBe 18L
        track.publishing_type shouldBe "Manga"
        track.start_date shouldBe "2020-01-01"
        track.score shouldBe 8.7
        track.score_votes shouldBe 2400
        track.tags shouldContainExactly listOf("悬疑", "漫画")
    }
}
