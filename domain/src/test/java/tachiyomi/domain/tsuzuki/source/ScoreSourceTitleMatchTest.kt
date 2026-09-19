package tachiyomi.domain.tsuzuki.source

import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.source.interactor.ScoreSourceTitleMatch

class ScoreSourceTitleMatchTest {

    private val scorer = ScoreSourceTitleMatch()

    @Test
    fun `exact title matches score 1_0`() {
        val score = scorer("Attack on Titan", "Attack on Titan")
        score shouldBe 1.0
    }

    @Test
    fun `accent normalization produces 1_0`() {
        val score = scorer("Café Love", "Cafe Love")
        score shouldBe 1.0
    }

    @Test
    fun `case differences produce 1_0`() {
        val score = scorer("ONE PIECE", "one piece")
        score shouldBe 1.0
    }

    @Test
    fun `punctuation differences produce 1_0`() {
        val score = scorer("Spy x Family!", "Spy x Family")
        score shouldBe 1.0
    }

    @Test
    fun `whitespace variations produce 1_0`() {
        val score = scorer("Chainsaw    Man", "Chainsaw Man")
        score shouldBe 1.0
    }

    @Test
    fun `slight typo scores high but below auto-accept threshold`() {
        val score = scorer("Attack on Titan", "Attack on Titn")
        score shouldBeGreaterThanOrEqual 0.70
        score shouldBeLessThan 0.97
    }

    @Test
    fun `unrelated title scores below confirmation threshold`() {
        val score = scorer("Naruto", "One Piece")
        score shouldBeLessThan 0.70
    }

    @Test
    fun `score is strictly bounded between 0_0 and 1_0`() {
        val score1 = scorer("", "Something")
        val score2 = scorer("Something", "")
        val score3 = scorer("A very long different string", "Short")

        score1 shouldBeGreaterThanOrEqual 0.0
        score1 shouldBeLessThanOrEqual 1.0

        score2 shouldBeGreaterThanOrEqual 0.0
        score2 shouldBeLessThanOrEqual 1.0

        score3 shouldBeGreaterThanOrEqual 0.0
        score3 shouldBeLessThanOrEqual 1.0
    }
}
