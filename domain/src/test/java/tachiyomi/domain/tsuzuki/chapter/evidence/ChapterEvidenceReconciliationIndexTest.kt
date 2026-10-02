package tachiyomi.domain.tsuzuki.chapter.evidence

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel

class ChapterEvidenceReconciliationIndexTest {

    @Test
    fun `index narrows external and mapped candidates and tracks replacements`() {
        val unrelated = (1..10_000).map { index ->
            persisted(
                id = "unrelated-$index",
                externalKey = "external-$index",
                mappedChapterId = "chapter-$index",
            )
        }
        val first = persisted(
            id = "first",
            producerId = "addon-a",
            externalKey = "shared-key",
            mappedChapterId = "target-chapter",
        )
        val second = persisted(
            id = "second",
            producerId = "addon-b",
            externalKey = "shared-key",
            mappedChapterId = "target-chapter",
        )
        val index = ChapterEvidenceReconciliationIndex(unrelated + first + second)

        index.externalKeyCandidates("shared-key").map { it.evidence.id } shouldContainExactly
            listOf("first", "second")
        index.mappedChapterCandidates("target-chapter").map { it.evidence.id } shouldContainExactly
            listOf("first", "second")

        val replacement = first.copy(
            evidence = first.evidence.copy(externalChapterKey = "moved-key"),
            mappedCanonicalChapterId = "moved-chapter",
        )
        index.replace(previous = first, current = replacement)

        index.externalKeyCandidates("shared-key").map { it.evidence.id } shouldContainExactly listOf("second")
        index.externalKeyCandidates("moved-key").map { it.evidence.id } shouldContainExactly listOf("first")
        index.mappedChapterCandidates("target-chapter").map { it.evidence.id } shouldContainExactly listOf("second")
        index.mappedChapterCandidates("moved-chapter").map { it.evidence.id } shouldContainExactly listOf("first")
    }

    @Test
    fun `parse cache parses each raw identity only once`() {
        var parseCalls = 0
        val parser = ParseCanonicalChapterLabel()
        val cache = ChapterEvidenceParseCache { rawLabel, rawNumber ->
            parseCalls++
            parser.execute(rawLabel, rawNumber)
        }
        val evidence = chapterEvidence(
            id = "evidence",
            externalKey = "chapter-4",
            rawLabel = "Chapter 4",
            rawNumber = 4.0,
        )

        repeat(100) {
            cache.parse(evidence).identity.baseNumber shouldBe 4
        }
        parseCalls shouldBe 1

        cache.parse(evidence.copy(rawNumber = 5.0))
        parseCalls shouldBe 2
    }

    private fun persisted(
        id: String,
        producerId: String = "addon",
        externalKey: String,
        mappedChapterId: String?,
    ) = PersistedChapterEvidence(
        evidence = chapterEvidence(
            id = id,
            producerId = producerId,
            externalKey = externalKey,
            rawLabel = "Chapter 1",
            rawNumber = 1.0,
        ),
        mappedCanonicalChapterId = mappedChapterId,
    )

    private fun chapterEvidence(
        id: String,
        producerId: String = "addon",
        externalKey: String,
        rawLabel: String,
        rawNumber: Double?,
    ) = ChapterEvidence(
        id = id,
        canonicalTitleId = "title",
        producerKind = ProducerKind.ADDON,
        producerId = producerId,
        externalChapterKey = externalKey,
        rawLabel = rawLabel,
        rawNumber = rawNumber,
        volume = null,
        title = null,
        observedAt = 1L,
        confidence = 1.0,
        authority = ChapterEvidenceAuthority.ADDON_PROVISIONAL,
    )
}
