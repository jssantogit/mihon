package eu.kanade.tachiyomi.ui.main

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CanonicalTitleSourceDiscoveryRouteTest {

    @Test
    fun `internal action routes the exact canonical title id`() {
        parseCanonicalTitleSourceDiscoveryRoute(
            action = MainActivity.ACTION_FIND_OR_ADD_READING_SOURCE,
            canonicalTitleId = "canonical-title-1",
        ) shouldBe MainActivityIntentRoute.DiscoverCanonicalTitleSources("canonical-title-1")

        parseCanonicalTitleSourceDiscoveryRoute(
            action = "external-or-unrelated-action",
            canonicalTitleId = "canonical-title-1",
        ) shouldBe null
    }

    @Test
    fun `missing or blank canonical title id is consumed without navigation`() {
        parseCanonicalTitleSourceDiscoveryRoute(
            action = MainActivity.ACTION_FIND_OR_ADD_READING_SOURCE,
            canonicalTitleId = null,
        ) shouldBe MainActivityIntentRoute.IgnoreInvalidCanonicalTitleDiscovery

        parseCanonicalTitleSourceDiscoveryRoute(
            action = MainActivity.ACTION_FIND_OR_ADD_READING_SOURCE,
            canonicalTitleId = " \t ",
        ) shouldBe MainActivityIntentRoute.IgnoreInvalidCanonicalTitleDiscovery
    }
}
