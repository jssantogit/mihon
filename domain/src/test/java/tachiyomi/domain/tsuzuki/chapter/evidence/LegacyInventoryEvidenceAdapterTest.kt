package tachiyomi.domain.tsuzuki.chapter.evidence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterVolume
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterInventory
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterSnapshot

class LegacyInventoryEvidenceAdapterTest {
    private val adapter = LegacyInventoryEvidenceAdapter(ParseCanonicalChapterVolume())

    @Test
    fun `two languages retain distinct stable source evidence across order and metadata changes`() {
        val en = inventory(101L, "en", "Vol. 1 Ch. 4")
        val pt = inventory(202L, "pt-BR", "Vol. 1 Ch. 4")
        val first = (adapter.adapt(en, 10L) + adapter.adapt(pt, 10L))
        val reversed = adapter.adapt(pt, 20L) + adapter.adapt(
            en.copy(chapters = en.chapters.map { it.copy(rawName = "Vol. 1 Ch. 4 - Revised") }),
            20L,
        )

        first shouldHaveSize 2
        first.map { it.id }.toSet() shouldBe reversed.map { it.id }.toSet()
        first.map { it.producerId }.toSet() shouldBe setOf("mihon-legacy:title:101", "mihon-legacy:title:202")
        first.map { it.externalChapterKey } shouldBe listOf("/chapter/4", "/chapter/4")
        first.map { it.volume } shouldBe listOf(1, 1)
        first.all { it.authority == ChapterEvidenceAuthority.ADDON_PROVISIONAL } shouldBe true
        first.all { it.producerKind == ProducerKind.ADDON } shouldBe true
        first.all { it.rawNumber == 4.0 } shouldBe true
        reversed.all { it.observedAt == 20L } shouldBe true
    }

    @Test
    fun `unqualified or contradictory volume labels do not invent a volume or verified status`() {
        val inventory = inventory(101L, "en", "Chapter 4").copy(
            chapters = listOf(
                snapshot(101L, "mapping-101", "/plain", "Chapter 4"),
                snapshot(101L, "mapping-101", "/ambiguous", "Vol. 1 Ch. 4 - Vol. 2 edition"),
                snapshot(101L, "mapping-101", "/unknown", "An unrelated release"),
            ),
        )
        val observations = adapter.adapt(inventory, 42L)
        observations shouldHaveSize 3
        observations.map { it.volume } shouldBe listOf(null, null, null)
        observations.single { it.externalChapterKey == "/unknown" }.rawNumber shouldBe 4.0
        observations.all { it.authority == ChapterEvidenceAuthority.ADDON_PROVISIONAL } shouldBe true
    }

    @Test
    fun `identical duplicate URLs collapse but contradictory duplicates fail closed`() {
        val initial = inventory(101L, "en", "Chapter 4")
        val duplicate = initial.copy(chapters = initial.chapters + initial.chapters.single())
        adapter.adapt(duplicate, 10L) shouldHaveSize 1

        val conflicting = duplicate.copy(
            chapters = duplicate.chapters.dropLast(1) +
                duplicate.chapters.last().copy(rawName = "Chapter 126"),
        )
        shouldThrow<IllegalArgumentException> { adapter.adapt(conflicting, 10L) }
    }

    @Test
    fun `invalid source and mapping identities fail without writing evidence`() {
        val valid = inventory(101L, "en", "Chapter 4")
        shouldThrow<IllegalArgumentException> {
            adapter.adapt(valid.copy(canonicalTitleId = ""), 10L)
        }
        shouldThrow<IllegalArgumentException> {
            adapter.adapt(valid.copy(chapters = valid.chapters.map { it.copy(sourceId = 202L) }), 10L)
        }
        shouldThrow<IllegalArgumentException> {
            adapter.adapt(valid.copy(chapters = valid.chapters.map { it.copy(sourceMappingId = "other") }), 10L)
        }
        shouldThrow<IllegalArgumentException> {
            adapter.adapt(valid.copy(chapters = valid.chapters.map { it.copy(sourceChapterId = "") }), 10L)
        }
        adapter.adapt(valid.copy(chapters = emptyList()), 10L) shouldHaveSize 0
    }

    private fun inventory(source: Long, language: String, label: String) = SourceChapterInventory(
        sourceMappingId = "mapping-" + source,
        sourceId = source,
        canonicalTitleId = "title",
        language = language,
        chapters = listOf(snapshot(source, "mapping-" + source, "/chapter/4", label)),
    )

    private fun snapshot(source: Long, mapping: String, url: String, label: String) =
        SourceChapterSnapshot(
            sourceId = source,
            sourceMappingId = mapping,
            sourceChapterId = url,
            rawName = label,
            rawNumberHint = 4.0,
        )
}
