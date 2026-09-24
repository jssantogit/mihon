package eu.kanade.presentation.tsuzuki.content

import eu.kanade.tachiyomi.ui.tsuzuki.content.ContentOptionPresentation
import eu.kanade.tachiyomi.ui.tsuzuki.content.ContentSelectorScreenState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.content.ContentOption

class ContentOptionSelectorDiscoveryTest {

    @Test
    fun `discovery is offered when selected chapter has no content options`() {
        val state = ContentSelectorScreenState.Empty(
            canonicalTitleId = "canonical-title",
            canonicalChapterId = "canonical-chapter",
        )

        state.shouldOfferSourceDiscovery() shouldBe true
    }

    @Test
    fun `discovery is offered after provider errors without converting failure to empty content`() {
        val state = ContentSelectorScreenState.Error(
            canonicalTitleId = "canonical-title",
            canonicalChapterId = "canonical-chapter",
            error = IllegalStateException("Provider failed"),
        )

        state.shouldOfferSourceDiscovery() shouldBe true
        state.shouldBeInstanceOf<ContentSelectorScreenState.Error>()
    }

    @Test
    fun `discovery is not offered while loading or when readable options exist`() {
        ContentSelectorScreenState.Loading.shouldOfferSourceDiscovery() shouldBe false
        ContentSelectorScreenState.Ready(
            canonicalTitleId = "canonical-title",
            canonicalChapterId = "canonical-chapter",
            options = listOf(
                ContentOptionPresentation(
                    option = ContentOption(
                        key = "reader:chapter",
                        canonicalChapterId = "canonical-chapter",
                        addonId = AddonId("reader"),
                        language = "en",
                        scanlationGroup = null,
                        releaseDate = null,
                        delivery = ContentDelivery.LocalArchive("content://chapter"),
                    ),
                    addonDisplayName = "Reader",
                    language = "en",
                    scanlationGroup = null,
                    releaseDate = null,
                ),
            ),
            preferredAddonId = null,
            preferredOptionKey = null,
            preferredLanguage = null,
            preferredUnavailable = false,
        ).shouldOfferSourceDiscovery() shouldBe false
    }
}
