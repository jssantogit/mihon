package eu.kanade.tachiyomi.data.tsuzuki.addon

import eu.kanade.tachiyomi.data.tsuzuki.diagnostics.RecordingChapterInventoryDiagnostics
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticOutcome
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticReason
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticStage
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterInventory
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterSnapshot
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentBindingAvailability
import tachiyomi.domain.tsuzuki.content.repository.ContentBindingRepository
import java.io.IOException
import java.net.SocketTimeoutException

class MihonChapterProbeProviderTest {

    @Test
    fun `diagnostic accounts for duplicate fractional and identity-less source rows`() = runTest {
        val binding = binding()
        val diagnostics = RecordingChapterInventoryDiagnostics()
        diagnostics.start("title")
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
                            snapshot(binding.id, 7L, "/chapter/1", "Chapter 1", 1.0),
                            snapshot(binding.id, 7L, "/chapter/1", "Chapter 1", 1.0),
                            snapshot(binding.id, 7L, "/chapter/1-5", "Chapter 1.5", 1.5),
                            snapshot(binding.id, 7L, "", "Chapter 2", 2.0).copy(sourceChapterUrl = ""),
                        ),
                        mihonMangaId = 99L,
                        language = "en",
                    ),
                )
            },
            clock = { 1234L },
            diagnostics = diagnostics,
        )

        val evidence = provider.probe("title").getOrThrow()

        evidence.size shouldBe 2
        evidence.map { it.rawNumber }.toSet() shouldBe setOf(1.0, 1.5)
        val event = diagnostics.events.single()
        event.stage shouldBe ChapterInventoryDiagnosticStage.PROBE
        event.outcome shouldBe ChapterInventoryDiagnosticOutcome.PARTIAL
        event.received shouldBe 4
        event.accepted shouldBe 2
        event.provisional shouldBe 2
        event.discarded shouldBe 2
        event.reasons[ChapterInventoryDiagnosticReason.DUPLICATE] shouldBe 1
        event.reasons[ChapterInventoryDiagnosticReason.MISSING_SOURCE_ID] shouldBe 1
        event.reasons[ChapterInventoryDiagnosticReason.MISSING_SOURCE_URL] shouldBe 1
        diagnostics.report().contains("/chapter/") shouldBe false
    }

    @Test
    fun `diagnostic records no binding separately from an empty extension inventory`() = runTest {
        val diagnostics = RecordingChapterInventoryDiagnostics()
        diagnostics.start("title")
        val provider = MihonChapterProbeProvider(
            addonId = AddonId("mangadex"),
            contentBindingRepository = FakeContentBindingRepository(emptyList()),
            parser = ParseCanonicalChapterLabel(),
            fetchInventory = { error("must not fetch") },
            clock = { 1L },
            diagnostics = diagnostics,
        )

        provider.probe("title").getOrThrow() shouldBe emptyList()

        val event = diagnostics.events.single()
        event.stage shouldBe ChapterInventoryDiagnosticStage.PROBE
        event.outcome shouldBe ChapterInventoryDiagnosticOutcome.NO_BINDING
        event.reasons[ChapterInventoryDiagnosticReason.NO_BINDING] shouldBe 1
    }

    @Test
    fun `diagnostic classifies wrapped timeouts and IO failures from their causes`() = runTest {
        val binding = binding()
        val diagnostics = RecordingChapterInventoryDiagnostics()
        diagnostics.start("title")

        val timeout = IllegalStateException("wrapper", SocketTimeoutException("private timeout"))
        val timeoutProvider = MihonChapterProbeProvider(
            addonId = AddonId("mangadex"),
            contentBindingRepository = FakeContentBindingRepository(listOf(binding)),
            parser = ParseCanonicalChapterLabel(),
            fetchInventory = { Result.failure(timeout) },
            clock = { 1L },
            diagnostics = diagnostics,
        )

        timeoutProvider.probe("title").isFailure shouldBe true
        diagnostics.events.single { it.stage == ChapterInventoryDiagnosticStage.PROBE }
            .outcome shouldBe ChapterInventoryDiagnosticOutcome.TIMEOUT

        diagnostics.clear()
        diagnostics.start("title")
        val network = IllegalStateException("wrapper", IOException("private network detail"))
        val networkProvider = MihonChapterProbeProvider(
            addonId = AddonId("mangadex"),
            contentBindingRepository = FakeContentBindingRepository(listOf(binding)),
            parser = ParseCanonicalChapterLabel(),
            fetchInventory = { Result.failure(network) },
            clock = { 1L },
            diagnostics = diagnostics,
        )

        networkProvider.probe("title").isFailure shouldBe true
        diagnostics.events.single { it.stage == ChapterInventoryDiagnosticStage.PROBE }
            .outcome shouldBe ChapterInventoryDiagnosticOutcome.NETWORK_ERROR
        diagnostics.report().contains("private network detail") shouldBe false
    }

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
    fun `multi-source inventories start concurrently`() = runTest {
        val bindings = listOf(
            binding(id = "binding-en"),
            binding(id = "binding-pt"),
        )
        val release = CompletableDeferred<Unit>()
        val started = mutableSetOf<String>()
        val provider = MihonChapterProbeProvider(
            addonId = AddonId("mangadex"),
            contentBindingRepository = FakeContentBindingRepository(bindings),
            parser = ParseCanonicalChapterLabel(),
            fetchInventory = { binding ->
                started += binding.id
                release.await()
                Result.success(
                    SourceChapterInventory(
                        sourceMappingId = binding.id,
                        sourceId = if (binding.id.endsWith("en")) 7L else 8L,
                        canonicalTitleId = "title",
                        chapters = emptyList(),
                        mihonMangaId = 99L,
                        language = "",
                    ),
                )
            },
            clock = { 1L },
        )

        val result = backgroundScope.async {
            provider.probe("title")
        }
        runCurrent()

        started.size shouldBe 2

        release.complete(Unit)
        result.await().getOrThrow() shouldBe emptyList()
    }

    @Test
    fun `one crashing inventory does not discard evidence from other bound sources`() = runTest {
        val bindings = listOf(binding("binding-en"), binding("binding-pt"))
        val provider = MihonChapterProbeProvider(
            addonId = AddonId("mangadex"),
            contentBindingRepository = FakeContentBindingRepository(bindings),
            parser = ParseCanonicalChapterLabel(),
            fetchInventory = { item ->
                if (item.id == "binding-en") error("Source unavailable")
                Result.success(
                    SourceChapterInventory(
                        sourceMappingId = item.id,
                        sourceId = 8L,
                        canonicalTitleId = "title",
                        chapters = listOf(
                            SourceChapterSnapshot(
                                sourceId = 8L,
                                sourceMappingId = item.id,
                                sourceChapterId = "/chapter-37",
                                rawName = "Chapter 37",
                                language = "pt-BR",
                                rawNumberHint = 37.0,
                            ),
                        ),
                        mihonMangaId = 99L,
                        language = "pt-BR",
                    ),
                )
            },
            clock = { 1L },
        )

        provider.probe("title").getOrThrow().single().externalChapterKey shouldBe "8:/chapter-37"
    }

    @Test
    fun `partial scan failure with no evidence remains retryable`() = runTest {
        val en = binding("binding-en")
        val pt = binding("binding-pt")
        val provider = MihonChapterProbeProvider(
            addonId = AddonId("mangadex"),
            contentBindingRepository = FakeContentBindingRepository(listOf(en, pt)),
            parser = ParseCanonicalChapterLabel(),
            fetchInventory = { item ->
                if (item.id == en.id) error("English inventory unavailable")
                Result.success(
                    SourceChapterInventory(
                        sourceMappingId = item.id,
                        sourceId = 8L,
                        canonicalTitleId = "title",
                        chapters = emptyList(),
                        mihonMangaId = 80L,
                        language = "pt-BR",
                    ),
                )
            },
            clock = { 1L },
        )

        provider.probe("title").exceptionOrNull()?.message shouldBe "English inventory unavailable"
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

    private fun binding(id: String = "binding") = ContentBinding(
        id = id,
        canonicalTitleId = "title",
        addonId = AddonId("mangadex"),
        providerTitleKey = "7:/dandadan/$id",
        matchConfidence = 1.0,
        verifiedByUser = false,
        availability = ContentBindingAvailability.AVAILABLE,
        runtimePayload = byteArrayOf(1),
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun snapshot(
        mappingId: String,
        sourceId: Long,
        chapterId: String,
        name: String,
        number: Double,
    ) = SourceChapterSnapshot(
        sourceId = sourceId,
        sourceMappingId = mappingId,
        sourceChapterId = chapterId,
        sourceChapterUrl = chapterId,
        rawName = name,
        language = "en",
        rawNumberHint = number,
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
