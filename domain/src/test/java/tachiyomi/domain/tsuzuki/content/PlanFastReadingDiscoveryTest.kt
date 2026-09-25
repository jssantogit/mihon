package tachiyomi.domain.tsuzuki.content

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon
import tachiyomi.domain.tsuzuki.addon.repository.AddonSourceEligibility
import tachiyomi.domain.tsuzuki.content.interactor.PlanFastReadingDiscovery

class PlanFastReadingDiscoveryTest {

    private val planner = PlanFastReadingDiscovery()

    @Test
    fun `one multilingual addon reserves a slot for English before second Portuguese source`() {
        val addon = installed("multi", 14L, 15L, 12L, 13L)
        val result = planner.execute(
            installed = listOf(addon),
            eligibility = mapOf(
                addon.id to listOf(
                    source(14L, "pt-BR"),
                    source(15L, "pt-BR"),
                    source(12L, "pt"),
                    source(13L, "en"),
                ),
            ),
            preferredAddonId = null,
            preferredLanguages = listOf("pt-BR", "pt", "en"),
        )

        result.size shouldBe 1
        result.single().allowedSourceIds.toList() shouldBe listOf(14L, 13L, 15L)
        result.single().batchSize shouldBe 3
    }

    @Test
    fun `first two addons cover Portuguese and English without scanning every installed package`() {
        val portuguese = installed("pt-small", 11L)
        val english = installed("en-small", 12L)
        val unrelated = installed("other", 13L)
        val result = planner.execute(
            installed = listOf(unrelated, english, portuguese),
            eligibility = mapOf(
                portuguese.id to listOf(source(11L, "pt-BR")),
                english.id to listOf(source(12L, "en")),
                unrelated.id to listOf(source(13L, "ru")),
            ),
            preferredAddonId = null,
            preferredLanguages = listOf("pt-BR", "pt", "en"),
        )

        result.map { it.addonId } shouldBe listOf(portuguese.id, english.id)
        result.flatMap { it.allowedSourceIds }.toSet() shouldBe setOf(11L, 12L)
        result.sumOf { it.batchSize } shouldBe 2
    }

    @Test
    fun `disabled internal IDs and unrelated languages are never planned`() {
        val addon = installed("mixed", 10L, 11L, 12L)
        val result = planner.execute(
            installed = listOf(addon),
            eligibility = mapOf(
                addon.id to listOf(
                    source(10L, "en", enabled = false),
                    source(11L, "pt-BR"),
                    source(12L, "ru"),
                    source(99L, "en"),
                ),
            ),
            preferredAddonId = null,
            preferredLanguages = listOf("en"),
        )

        result shouldBe emptyList()
    }

    @Test
    fun `the users enabled preferred addon precedes a smaller package`() {
        val preferred = installed("preferred", 10L, 11L, 12L)
        val small = installed("small", 20L)
        val result = planner.execute(
            installed = listOf(small, preferred),
            eligibility = mapOf(
                preferred.id to listOf(source(11L, "en")),
                small.id to listOf(source(20L, "en")),
            ),
            preferredAddonId = preferred.id,
            preferredLanguages = listOf("en"),
        )

        result.first().addonId shouldBe preferred.id
        result.sumOf { it.batchSize } shouldBe 2
    }

    @Test
    fun `initial budgets are respected even for two large multilingual packages`() {
        val first = installed("first", 1L, 2L, 3L, 4L, 5L)
        val second = installed("second", 6L, 7L, 8L, 9L, 10L)
        val result = planner.execute(
            installed = listOf(first, second),
            eligibility = mapOf(
                first.id to listOf(
                    source(1L, "pt-BR"), source(2L, "en"), source(3L, "pt"),
                    source(4L, "en"), source(5L, "pt-BR"),
                ),
                second.id to listOf(
                    source(6L, "pt-BR"), source(7L, "en"), source(8L, "pt"),
                    source(9L, "en"), source(10L, "pt-BR"),
                ),
            ),
            preferredAddonId = null,
            preferredLanguages = listOf("pt-BR", "pt", "en"),
        )

        result.size shouldBe 2
        result.sumOf { it.batchSize } shouldBe 4
        result.all { it.allowedSourceIds.size <= 2 } shouldBe true
    }

    private fun installed(name: String, vararg ids: Long) = InstalledAddon(
        id = AddonId(name),
        displayName = name,
        enabled = true,
        versionName = "1.0",
        mihonSourceIds = ids.toList(),
        hasSettings = false,
    )

    private fun source(id: Long, language: String, enabled: Boolean = true) =
        AddonSourceEligibility(id, language, enabled)
}
