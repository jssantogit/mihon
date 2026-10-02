package tachiyomi.domain.tsuzuki.chapter.refresh

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterInventory
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterSnapshot

class ChapterInventoryFingerprintTest {

    @Test
    fun `fingerprint ignores inventory ordering but changes with identity input`() {
        val first = snapshot("/chapter/1", "Chapter 1", 1.0)
        val second = snapshot("/chapter/2", "Chapter 2", 2.0)
        val inventory = inventory(listOf(first, second))

        ChapterInventoryFingerprint.compute(inventory) shouldBe
            ChapterInventoryFingerprint.compute(inventory.copy(chapters = listOf(second, first)))

        ChapterInventoryFingerprint.compute(inventory) shouldNotBe
            ChapterInventoryFingerprint.compute(
                inventory.copy(chapters = listOf(first.copy(rawName = "Chapter 1.5"), second)),
            )
    }

    private fun inventory(chapters: List<SourceChapterSnapshot>) = SourceChapterInventory(
        sourceMappingId = "binding",
        sourceId = 7L,
        canonicalTitleId = "title",
        chapters = chapters,
        mihonMangaId = 99L,
        language = "en",
        sourceUrl = "/work",
        fetchStartedAtMillis = 100L,
    )

    private fun snapshot(id: String, name: String, number: Double) = SourceChapterSnapshot(
        sourceId = 7L,
        sourceMappingId = "binding",
        sourceChapterId = id,
        sourceChapterUrl = id,
        rawName = name,
        language = "en",
        rawNumberHint = number,
        mihonMangaId = 99L,
    )
}
