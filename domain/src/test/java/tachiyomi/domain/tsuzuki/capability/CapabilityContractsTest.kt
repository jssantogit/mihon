package tachiyomi.domain.tsuzuki.capability

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
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

    @Test
    fun `legacy canonical chapters default to provisional confirmation`() {
        val chapter = CanonicalChapter(
            id = "chapter-1",
            canonicalTitleId = "title-1",
            displayNumber = "1",
        )

        chapter.confirmation shouldBe CanonicalChapterConfirmation.PROVISIONAL
    }

    @Test
    fun `torrent delivery keeps explicit file selection evidence`() {
        val delivery = ContentDelivery.Torrent(
            infoHash = "abc123",
            magnetUri = "magnet:?xt=urn:btih:abc123",
            fileIndex = 4,
            filePath = "Volume 01/chapter05.cbz",
        )

        delivery.fileIndex shouldBe 4
        delivery.filePath shouldBe "Volume 01/chapter05.cbz"
    }
}
