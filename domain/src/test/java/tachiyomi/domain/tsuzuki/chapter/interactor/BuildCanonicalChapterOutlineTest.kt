package tachiyomi.domain.tsuzuki.chapter.interactor

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.metadata.ReportedChapterCount

class BuildCanonicalChapterOutlineTest {

    @Test
    fun `Death Note metadata count provides 108 rows without fabricating provider releases`() {
        val outline = buildCanonicalChapterOutline("death-note", emptyList(), listOf(count("death-note", 108)))
        outline.size shouldBe 108
        outline.map { it.chapter.baseNumber } shouldBe (1..108).toList()
        outline.all(CanonicalChapterOutlineEntry::inferredFromReportedCount) shouldBe true
        outline.first().chapter.id shouldBe inferredChapterId("death-note", 1)
        outline.last().chapter.id shouldBe inferredChapterId("death-note", 108)
    }

    @Test
    fun `Nanatsu metadata count provides 346 ordered lazy-compatible rows`() {
        val outline = buildCanonicalChapterOutline("nanatsu", emptyList(), listOf(count("nanatsu", 346)))
        outline.size shouldBe 346
        outline.first().chapter.displayNumber shouldBe "1"
        outline.last().chapter.displayNumber shouldBe "346"
    }

    @Test
    fun `real chapters replace count positions without losing extras`() {
        val observed = (1..62).map(::chapter) +
            (1..10).map { fraction -> chapter(1, part = fraction) }
        val result = buildCanonicalChapterOutline("title", observed, listOf(count("title", 64)))
        result.size shouldBe 74
        result.count(CanonicalChapterOutlineEntry::inferredFromReportedCount) shouldBe 2
        result.filter(CanonicalChapterOutlineEntry::inferredFromReportedCount)
            .map { it.chapter.baseNumber }
            .shouldContainExactly(63, 64)
        result.first { it.chapter.baseNumber == 1 && it.chapter.part == null }
            .chapter.id shouldBe "chapter-1"
    }

    @Test
    fun `zero and fractional actual identities sort before later integer slots`() {
        val outline = buildCanonicalChapterOutline(
            "title",
            listOf(chapter(0), chapter(0, part = 5), chapter(1), chapter(1, part = 5)),
            listOf(count("title", 1)),
        )
        outline.map { it.chapter.displayNumber }.shouldContainExactly("0", "0.5", "1", "1.5")
        outline.none(CanonicalChapterOutlineEntry::inferredFromReportedCount) shouldBe true
    }

    @Test
    fun `inferred slots are deterministic and disappear when evidence for number arrives`() {
        val metadata = listOf(count("title", 3))
        val initial = buildCanonicalChapterOutline("title", emptyList(), metadata)
        val discovered = buildCanonicalChapterOutline("title", listOf(chapter(2)), metadata)
        initial[1].chapter.id shouldBe inferredChapterId("title", 2)
        discovered[1].chapter.id shouldBe "chapter-2"
        discovered.size shouldBe 3
        discovered.count(CanonicalChapterOutlineEntry::inferredFromReportedCount) shouldBe 2
    }

    @Test
    fun `different provider counts never duplicate numbered slots or use impossible counts`() {
        val outline = buildCanonicalChapterOutline(
            "title",
            listOf(chapter(1)),
            listOf(
                count("title", 3, "kitsu"),
                count("title", 2, "mal"),
                count("title", -100, "invalid"),
                count("different-title", 500, "other"),
            ),
        )
        outline.map { it.chapter.baseNumber }.shouldContainExactly(1, 2, 3)
        outline.count(CanonicalChapterOutlineEntry::inferredFromReportedCount) shouldBe 2
        buildCanonicalChapterOutline("title", emptyList(), listOf(count("title", 100_000))).size shouldBe 5_000
    }

    private fun count(title: String, value: Int, provider: String = "kitsu") =
        ReportedChapterCount(title, provider, value, 1L)

    private fun chapter(number: Int, part: Int? = null): CanonicalChapter = CanonicalChapter(
        id = "chapter-$number" + (part?.let { "-$it" } ?: ""),
        canonicalTitleId = "title",
        displayNumber = number.toString() + (part?.let { ".$it" } ?: ""),
        type = CanonicalChapterType.REGULAR,
        baseNumber = number,
        part = part,
        confidence = 1.0,
    )
}
