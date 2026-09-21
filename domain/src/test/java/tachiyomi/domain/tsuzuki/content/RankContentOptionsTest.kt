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
    fun `downloaded local artifact ranks above preferred remote addon`() {
        val local = option(
            addon = "local",
            language = "en",
            key = "local",
            delivery = ContentDelivery.LocalArchive("content://chapter.cbz"),
        )
        val remote = option(
            addon = "mangadex",
            language = "en",
            key = "remote",
            delivery = ContentDelivery.Mihon(sourceId = 7L, mangaId = 8L, chapterId = 9L),
        )

        val ranked = RankContentOptions().execute(
            options = listOf(remote, local),
            preferredAddonId = AddonId("mangadex"),
            preferredLanguages = emptyList(),
        )

        ranked.map { it.key } shouldBe listOf("local", "remote")
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
        delivery: ContentDelivery = ContentDelivery.LocalArchive("content://$key"),
    ) = ContentOption(
        key = key,
        canonicalChapterId = "chapter",
        addonId = AddonId(addon),
        language = language,
        scanlationGroup = null,
        releaseDate = null,
        delivery = delivery,
    )
}
