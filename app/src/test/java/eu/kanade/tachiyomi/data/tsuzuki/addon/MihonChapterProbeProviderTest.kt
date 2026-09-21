package eu.kanade.tachiyomi.data.tsuzuki.addon

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterInventory
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterSnapshot
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentBindingAvailability
import tachiyomi.domain.tsuzuki.content.repository.ContentBindingRepository

class MihonChapterProbeProviderTest {

    @Test
    fun `source chapter ahead of integration becomes addon provisional evidence`() = runTest {
        val binding = binding()
        val provider = MihonChapterProbeProvider(
            addonId = AddonId("mangadex"),
            contentBindingRepository = FakeContentBindingRepository(listOf(binding)),
            parser = ParseCanonicalChapterLabel(),
            fetchInventory = {
                Result.success(
                    SourceChapterInventory(
                        sourceMappingId = binding.id,
                        sourceId = 7L,
                        canonicalTitleId = "title",
                        chapters = listOf(
                            SourceChapterSnapshot(
                                sourceId = 7L,
                                sourceMappingId = binding.id,
                                sourceChapterId = "/chapter-211",
                                rawName = "Chapter 211",
                                language = "en",
                                rawNumberHint = 211.0,
                            ),
                        ),
                        mihonMangaId = 99L,
                        language = "en",
                    ),
                )
            },
            clock = { 1234L },
        )

        val evidence = provider.probe("title").getOrThrow()
        val newChapter = evidence.single { it.rawLabel == "Chapter 211" }

        newChapter.canonicalTitleId shouldBe "title"
        newChapter.authority shouldBe ChapterEvidenceAuthority.ADDON_PROVISIONAL
        newChapter.producerKind shouldBe ProducerKind.ADDON
        newChapter.producerId shouldBe "mangadex"
        newChapter.externalChapterKey shouldBe "7:/chapter-211"
        newChapter.rawNumber shouldBe 211.0
        newChapter.observedAt shouldBe 1234L
    }

    @Test
    fun `background probe without persisted binding does not search or fetch broadly`() = runTest {
        var fetchCalls = 0
        val provider = MihonChapterProbeProvider(
            addonId = AddonId("mangadex"),
            contentBindingRepository = FakeContentBindingRepository(emptyList()),
            parser = ParseCanonicalChapterLabel(),
            fetchInventory = {
                fetchCalls++
                error("must not fetch without persisted binding")
            },
            clock = { 1L },
        )

        provider.probe("title").getOrThrow() shouldBe emptyList()
        fetchCalls shouldBe 0
    }

    private fun binding() = ContentBinding(
        id = "binding",
        canonicalTitleId = "title",
        addonId = AddonId("mangadex"),
        providerTitleKey = "7:/dandadan",
        matchConfidence = 1.0,
        verifiedByUser = false,
        availability = ContentBindingAvailability.AVAILABLE,
        runtimePayload = byteArrayOf(1),
        createdAt = 1L,
        updatedAt = 1L,
    )

    private class FakeContentBindingRepository(
        private val bindings: List<ContentBinding>,
    ) : ContentBindingRepository {
        override suspend fun get(canonicalTitleId: String, addonId: AddonId): ContentBinding? =
            bindings.lastOrNull { it.canonicalTitleId == canonicalTitleId && it.addonId == addonId }

        override suspend fun getByTitle(canonicalTitleId: String): List<ContentBinding> =
            bindings.filter { it.canonicalTitleId == canonicalTitleId }

        override suspend fun upsert(binding: ContentBinding) = error("probe must not write bindings")
        override suspend fun markUnavailable(bindingId: String, updatedAt: Long) = error("probe must not write")
    }
}
