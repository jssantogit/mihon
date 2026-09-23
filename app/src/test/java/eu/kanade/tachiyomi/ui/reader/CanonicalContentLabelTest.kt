package eu.kanade.tachiyomi.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.content.ContentOption

class CanonicalContentLabelTest {
    @Test
    fun `automatically chosen content shows its source language and scanlator`() {
        val option = ContentOption(
            key = "fire:en:chapter-1",
            canonicalChapterId = "chapter-1",
            addonId = AddonId("mangafire"),
            language = "en",
            scanlationGroup = "Group",
            releaseDate = null,
            delivery = ContentDelivery.Mihon(sourceId = 1L, mangaId = 2L, chapterId = 3L),
        )
        canonicalContentLabel("MangaFire", option) shouldBe "MangaFire · en · Group"
    }

    @Test
    fun `offline chapters have an explicit local label`() {
        canonicalContentLabel(null, null) shouldBe "Local"
    }
}
