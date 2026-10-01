package tachiyomi.domain.tsuzuki.integration.interactor

import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.integration.model.RatingIdentityEvidence
import tachiyomi.domain.tsuzuki.integration.model.TsuzukiRatingSource

class ComputeTsuzukiRatingTest {

    @Test
    fun `normalizes mixed provider scales to ten before averaging`() {
        val rating = ComputeTsuzukiRating(
            listOf(
                source("mal", 8.7, 10.0),
                source("kitsu", 86.0, 100.0),
                source("mangaupdates", 8.4, 10.0),
                source("bangumi", 8.1, 10.0),
            ),
        )

        rating?.sourceCount shouldBe 4
        rating?.value?.shouldBeExactly(8.45)
        rating?.maxValue shouldBe 10.0
    }

    @Test
    fun `a single provider never produces a Tsuzuki Rating`() {
        ComputeTsuzukiRating(
            listOf(source("mal", 8.9, 10.0)),
        ) shouldBe null
    }

    @Test
    fun `provider vote counts do not weight the aggregate`() {
        val rating = ComputeTsuzukiRating(
            listOf(
                source("mal", 10.0, 10.0, voteCount = 1_000_000),
                source("kitsu", 50.0, 100.0, voteCount = 10),
            ),
        )

        rating?.value shouldBe 7.5
    }

    @Test
    fun `duplicate provider cannot gain extra weight`() {
        val rating = ComputeTsuzukiRating(
            listOf(
                source("mal", 8.0, 10.0),
                source("mal", 10.0, 10.0),
                source("kitsu", 80.0, 100.0),
            ),
        )

        rating?.sourceCount shouldBe 2
        rating?.value shouldBe 8.0
    }

    @Test
    fun `corroborated rating-only evidence contributes but remains explicit`() {
        val rating = ComputeTsuzukiRating(
            listOf(
                source("mal", 8.0, 10.0),
                source(
                    "mangaupdates",
                    9.0,
                    10.0,
                    evidence = RatingIdentityEvidence.CORROBORATED_RATING_ONLY,
                ),
            ),
        )

        rating?.verifiedSourceCount shouldBe 1
        rating?.corroboratedSourceCount shouldBe 1
        rating?.sources?.last()?.identityEvidence shouldBe RatingIdentityEvidence.CORROBORATED_RATING_ONLY
    }

    @Test
    fun `supports every expected aggregate source count from two through six`() {
        (2..6).forEach { count ->
            val sources = (1..count).map { index ->
                source("provider-$index", 8.0, 10.0)
            }

            val rating = ComputeTsuzukiRating(sources)

            rating?.sourceCount shouldBe count
            rating?.value shouldBe 8.0
        }
    }

    @Test
    fun `invalid scale or out-of-range scores fail closed`() {
        ComputeTsuzukiRating(
            listOf(
                source("mal", 8.0, 10.0),
                source("kitsu", 120.0, 100.0),
            ),
        ) shouldBe null
    }

    private fun source(
        providerId: String,
        value: Double,
        maxValue: Double,
        voteCount: Int? = null,
        evidence: RatingIdentityEvidence = RatingIdentityEvidence.VERIFIED,
    ) = TsuzukiRatingSource(
        providerId = providerId,
        value = value,
        maxValue = maxValue,
        voteCount = voteCount,
        identityEvidence = evidence,
    )
}
