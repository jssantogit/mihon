package eu.kanade.tachiyomi.ui.tsuzuki.detail

import eu.kanade.tachiyomi.ui.tsuzuki.detail.shouldAutoOpenSourceBindingFlow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CanonicalTitleSourceBindingLaunchTest {

    @Test
    fun `requested binding flow opens once for the same canonical title`() {
        shouldAutoOpenSourceBindingFlow(
            canonicalTitleId = "canonical-title-1",
            requested = true,
            alreadyOpened = false,
        ) shouldBe true

        shouldAutoOpenSourceBindingFlow(
            canonicalTitleId = "canonical-title-1",
            requested = true,
            alreadyOpened = true,
        ) shouldBe false
    }

    @Test
    fun `binding flow does not open when request or canonical title is invalid`() {
        shouldAutoOpenSourceBindingFlow(
            canonicalTitleId = "canonical-title-1",
            requested = false,
            alreadyOpened = false,
        ) shouldBe false

        shouldAutoOpenSourceBindingFlow(
            canonicalTitleId = " \t ",
            requested = true,
            alreadyOpened = false,
        ) shouldBe false
    }
}
