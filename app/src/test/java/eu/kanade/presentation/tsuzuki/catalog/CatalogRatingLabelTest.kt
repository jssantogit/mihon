package eu.kanade.presentation.tsuzuki.catalog

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogScore

class CatalogRatingLabelTest {

    @Test
    fun `ratings keep each provider native scale instead of normalizing to percent`() {
        catalogRatingLabel(
            CatalogScore(
                provider = "kitsu",
                value = 81.4,
                maxValue = 100.0,
            ),
        ) shouldBe "81.4%"

        catalogRatingLabel(
            CatalogScore(
                provider = "mal",
                value = 8.72,
                maxValue = 10.0,
            ),
        ) shouldBe "8.72/10"

        catalogRatingLabel(
            CatalogScore(
                provider = "custom",
                value = 4.25,
                maxValue = 5.0,
            ),
        ) shouldBe "4.25/5"

        catalogRatingLabel(
            CatalogScore(
                provider = "custom",
                value = 81.4,
                maxValue = 100.0,
            ),
        ) shouldBe "81.4/100"
    }
}
