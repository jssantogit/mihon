package tachiyomi.domain.tsuzuki.capability

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.content.ContentOption
import tachiyomi.domain.tsuzuki.integration.IntegrationId

class CapabilityContractsTest {

    @Test
    fun `provider ids keep integration and addon namespaces distinct`() {
        val integration = IntegrationId("kitsu")
        val addon = AddonId("kitsu")

        integration.value shouldBe "kitsu"
        addon.value shouldBe "kitsu"
        integration shouldNotBe addon
    }

    @Test
    fun `content option always points to a canonical chapter and addon`() {
        val option = ContentOption(
            key = "mangadex:chapter-1:en",
            canonicalChapterId = "chapter-1",
            addonId = AddonId("mangadex"),
            language = "en",
            scanlationGroup = "Group",
            releaseDate = 100L,
            delivery = ContentDelivery.Mihon(
                sourceId = 1L,
                mangaId = 2L,
                chapterId = 3L,
            ),
        )

        option.canonicalChapterId shouldBe "chapter-1"
        option.addonId shouldBe AddonId("mangadex")
    }
}
