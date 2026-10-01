package tachiyomi.domain.tsuzuki.integration.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem

class RatingOnlyCandidateMatcherTest {

    @Test
    fun `exact title and publication year can corroborate an ephemeral rating match`() {
        val item = CatalogItem(
            provider = "kitsu",
            providerId = "1",
            title = "Star-Embracing Swordmaster",
            startDate = "2023-09-19",
        )
        val candidate = CatalogItem(
            provider = "mangaupdates",
            providerId = "42",
            title = "Star Embracing Swordmaster",
            startDate = "2023",
        )

        matchRatingOnlyCandidate(item, listOf(candidate)) shouldBe candidate
    }

    @Test
    fun `title equality alone never proves a rating-only match`() {
        val item = CatalogItem(
            provider = "kitsu",
            providerId = "1",
            title = "Same Title",
        )
        val candidate = CatalogItem(
            provider = "bangumi",
            providerId = "2",
            title = "Same Title",
        )

        matchRatingOnlyCandidate(item, listOf(candidate)) shouldBe null
    }

    @Test
    fun `creator identity can corroborate a rating-only match when year is unavailable`() {
        val item = CatalogItem(
            provider = "mal",
            providerId = "1",
            title = "Vagabond",
            authors = listOf("Takehiko Inoue"),
        )
        val candidate = CatalogItem(
            provider = "bangumi",
            providerId = "2",
            title = "Vagabond",
            authors = listOf("Takehiko Inoue"),
        )

        matchRatingOnlyCandidate(item, listOf(candidate)) shouldBe candidate
    }

    @Test
    fun `ambiguous corroborated candidates fail closed`() {
        val item = CatalogItem(
            provider = "mal",
            providerId = "1",
            title = "Duplicate",
            startDate = "2020-01-01",
        )
        val candidates = listOf(
            CatalogItem("mangaupdates", "2", "Duplicate", startDate = "2020"),
            CatalogItem("mangaupdates", "3", "Duplicate", startDate = "2020"),
        )

        matchRatingOnlyCandidate(item, candidates) shouldBe null
    }
}
