package eu.kanade.tachiyomi.ui.main

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MainActivityCanonicalTitleRouteTest {

    @Test
    fun `canonical title action resolves to canonical screen route`() {
        parseCanonicalTitleOpenRoute(
            action = MainActivity.ACTION_OPEN_CANONICAL_TITLE,
            canonicalTitleId = "canonical-123",
        ) shouldBe MainActivityCanonicalTitleRoute.Open("canonical-123")
    }

    @Test
    fun `blank canonical title id fails closed`() {
        parseCanonicalTitleOpenRoute(
            action = MainActivity.ACTION_OPEN_CANONICAL_TITLE,
            canonicalTitleId = " ",
        ) shouldBe MainActivityCanonicalTitleRoute.IgnoreInvalid
    }

    @Test
    fun `unrelated actions are ignored`() {
        parseCanonicalTitleOpenRoute(
            action = "other",
            canonicalTitleId = "canonical-123",
        ) shouldBe null
    }
}
