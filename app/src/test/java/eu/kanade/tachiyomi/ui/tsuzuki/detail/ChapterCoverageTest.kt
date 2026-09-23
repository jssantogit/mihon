package eu.kanade.tachiyomi.ui.tsuzuki.detail

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority
import tachiyomi.domain.tsuzuki.chapter.evidence.PersistedChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
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

    @Test
    fun `One Punch-Man reports MangaFire 138 plus without inventing earlier chapters`() {
        observedAddonCoverage(
            chapters = listOf(chapter(138), chapter(139)),
            evidence = listOf(
                evidence("fire", "chapter-138", "en-138"),
                evidence("fire", "chapter-139", "en-139"),
                evidence("fire", "chapter-139", "pt-139"),
            ),
            addonNames = mapOf("fire" to "MangaFire"),
        ) shouldBe listOf(ObservedAddonCoverage("fire", "MangaFire", 2, 138, 139))
    }

    @Test
    fun `Death Note Kitsu chapter count cannot synthesize Add-on coverage`() {
        observedAddonCoverage(
            chapters = emptyList(),
            evidence = emptyList(),
            addonNames = mapOf("fire" to "MangaFire"),
        ) shouldBe emptyList()
    }

    private fun evidence(addonId: String, chapterId: String, id: String) =
        PersistedChapterEvidence(
            evidence = ChapterEvidence(
                id = id,
                canonicalTitleId = "title",
                producerKind = ProducerKind.ADDON,
                producerId = addonId,
                externalChapterKey = id,
                rawLabel = chapterId,
                rawNumber = null,
                volume = null,
                title = null,
                observedAt = 1L,
                confidence = 1.0,
                authority = ChapterEvidenceAuthority.ADDON_PROVISIONAL,
            ),
            mappedCanonicalChapterId = chapterId,
        )

    private fun chapter(number: Int, display: String = number.toString()) =
        CanonicalChapter(
            id = "chapter-$display",
            canonicalTitleId = "title",
            displayNumber = display,
            baseNumber = number,
        )
}
