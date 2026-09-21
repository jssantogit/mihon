package tachiyomi.domain.tsuzuki.content

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.content.interactor.RankContentOptions

class RankContentOptionsTest {

    @Test
    fun `preferred language reorders but never filters alternatives`() {
        val ranked = RankContentOptions().execute(
            options = listOf(option("mangadex", "en"), option("mangafire", "pt-BR")),
            preferredAddonId = null,
            preferredLanguages = listOf("pt-BR"),
        )

        ranked.map { it.language } shouldBe listOf("pt-BR", "en")
        ranked.size shouldBe 2
    }

    @Test
    fun `ranking is deterministic for otherwise equal options`() {
        val options = listOf(
            option("zeta", "en", key = "b"),
            option("alpha", "en", key = "c"),
            option("alpha", "en", key = "a"),
        )

        val first = RankContentOptions().execute(options, null, emptyList())
        val second = RankContentOptions().execute(options.reversed(), null, emptyList())

        first.map { it.key } shouldBe listOf("a", "c", "b")
        second.map { it.key } shouldBe first.map { it.key }
    }

    private fun option(
        addon: String,
        language: String,
        key: String = addon + ":" + language,
    ) = ContentOption(
        key = key,
        canonicalChapterId = "chapter",
        addonId = AddonId(addon),
        language = language,
        scanlationGroup = null,
        releaseDate = null,
        delivery = ContentDelivery.LocalArchive("content://$key"),
    )
}
