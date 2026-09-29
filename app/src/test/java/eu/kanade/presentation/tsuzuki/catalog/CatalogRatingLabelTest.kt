package eu.kanade.presentation.tsuzuki.catalog

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogScore

class CatalogRatingLabelTest {

    @Test
    fun `ratings are normalized to percent and keep provider provenance`() {
        catalogRatingLabel(
            CatalogScore(
                provider = "kitsu",
                value = 81.4,
                maxValue = 100.0,
            ),
        ) shouldBe "★ 81% · Kitsu"

        catalogRatingLabel(
            CatalogScore(
                provider = "mal",
                value = 8.72,
                maxValue = 10.0,
            ),
        ) shouldBe "★ 87% · MAL"
    }
}
