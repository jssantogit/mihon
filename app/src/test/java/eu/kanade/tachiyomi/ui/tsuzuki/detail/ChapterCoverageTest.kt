package eu.kanade.tachiyomi.ui.tsuzuki.detail

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter

class ChapterCoverageTest {

    @Test
    fun `One Punch-Man partial provider inventory starts at actual observed chapter`() {
        firstDiscoveredChapterNumber(listOf(chapter(138), chapter(139))) shouldBe 138
    }

    @Test
    fun `Death Note metadata count alone cannot synthesize chapter coverage`() {
        firstDiscoveredChapterNumber(emptyList()) shouldBe null
    }

    @Test
    fun `Hunter x Hunter numbered zero is retained ahead of fractional chapter`() {
        firstDiscoveredChapterNumber(
            listOf(chapter(0), chapter(0, "0.5"), chapter(1)),
        ) shouldBe 0
    }

    private fun chapter(number: Int, display: String = number.toString()) =
        CanonicalChapter(
            id = "chapter-$display",
            canonicalTitleId = "title",
            displayNumber = display,
            baseNumber = number,
        )
}
