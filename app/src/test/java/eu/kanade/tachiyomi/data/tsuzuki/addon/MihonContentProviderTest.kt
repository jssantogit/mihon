package eu.kanade.tachiyomi.data.tsuzuki.addon

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticEvent
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticOutcome
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticReason
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticStage
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.evidence.PersistedChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.interactor.inferredChapterId
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterInventory
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterSnapshot
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentBindingAvailability
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.content.repository.ContentBindingRepository

class MihonContentProviderTest {

    @Test
    fun `content provider explains when no binding exists instead of silently returning empty`() = runTest {
        val diagnostic = RecordingDiagnostics()
        diagnostic.start("title")
        val provider = MihonContentProvider(
            addonId = AddonId("mangafire"),
            contentBindingRepository = FakeContentBindingRepository(emptyList()),
            canonicalChapterRepository = FakeCanonicalChapterRepository(variants = emptyList()),
            parser = ParseCanonicalChapterLabel(),
            fetchInventory = { error("Must not fetch without a binding") },
            materializeDelivery = { _, _ -> error("Must not materialize without a binding") },
            diagnostics = diagnostic,
        )

        provider.resolve("title", "canonical-chapter-37").getOrThrow() shouldBe emptyList()
        val event = diagnostic.events.single()
        event.stage shouldBe ChapterInventoryDiagnosticStage.CONTENT_PROVIDER
        event.outcome shouldBe ChapterInventoryDiagnosticOutcome.NO_BINDING
        event.reasons[ChapterInventoryDiagnosticReason.NO_BINDING] shouldBe 1
    }

    @Test
    fun `matching source release becomes content option for canonical chapter`() = runTest {
        val binding = binding(id = "binding-pt", sourceKey = "7:/dandadan")
        val chapterRepository = FakeCanonicalChapterRepository(
            variants = emptyList(),
        )
        val provider = MihonContentProvider(
            addonId = AddonId("mangadex"),
            contentBindingRepository = FakeContentBindingRepository(listOf(binding)),
            canonicalChapterRepository = chapterRepository,
            parser = ParseCanonicalChapterLabel(),
            fetchInventory = {
                Result.success(
                    inventory(
                        bindingId = it.id,
                        sourceId = 7L,
                        language = "pt-BR",
                        snapshot = snapshot(
                            sourceId = 7L,
                            bindingId = it.id,
                            sourceChapterId = "/chapter-37",
                            language = "pt-BR",
                        ),
                    ),
                )
            },
            materializeDelivery = { _, _ ->
                Result.success(ContentDelivery.Mihon(sourceId = 7L, mangaId = 99L, chapterId = 123L))
            },
            chapterEvidenceRepository = FakeChapterEvidenceRepository(
                listOf(
                    PersistedChapterEvidence(
                        evidence = ChapterEvidence(
                            id = "evidence-37",
                            canonicalTitleId = "title",
                            producerKind = ProducerKind.ADDON,
                            producerId = "mangadex",
                            externalChapterKey = "7:/chapter-37",
                            rawLabel = "Chapter 37",
                            rawNumber = 37.0,
                            volume = null,
                            title = null,
                            observedAt = 1L,
                            confidence = 1.0,
                            authority = ChapterEvidenceAuthority.ADDON_PROVISIONAL,
                        ),
                        mappedCanonicalChapterId = "canonical-chapter-37",
                    ),
                ),
            ),
        )

        val options = provider.resolve("title", "canonical-chapter-37").getOrThrow()

        options.size shouldBe 1
        options.single().addonId shouldBe AddonId("mangadex")
        options.single().canonicalChapterId shouldBe "canonical-chapter-37"
        options.single().language shouldBe "pt-BR"
        options.single().delivery shouldBe ContentDelivery.Mihon(7L, 99L, 123L)
        chapterRepository.writeCount shouldBe 0
    }

    @Test
    fun `one addon keeps content options from multiple bound internal sources`() = runTest {
        val en = binding(id = "binding-en", sourceKey = "7:/dandadan")
        val pt = binding(id = "binding-pt", sourceKey = "8:/dandadan")
        val chapterRepository = FakeCanonicalChapterRepository(
            variants = listOf(
                variant(sourceId = 7L, sourceChapterId = "/en-37"),
                variant(sourceId = 8L, sourceChapterId = "/pt-37"),
            ),
        )
        val provider = MihonContentProvider(
            addonId = AddonId("mangadex"),
            contentBindingRepository = FakeContentBindingRepository(listOf(en, pt)),
            canonicalChapterRepository = chapterRepository,
            parser = ParseCanonicalChapterLabel(),
            fetchInventory = { binding ->
                val isEnglish = binding.id == "binding-en"
                val sourceId = if (isEnglish) 7L else 8L
                val language = if (isEnglish) "en" else "pt-BR"
                val chapterId = if (isEnglish) "/en-37" else "/pt-37"
                Result.success(
                    inventory(
                        bindingId = binding.id,
                        sourceId = sourceId,
                        language = language,
                        snapshot = snapshot(sourceId, binding.id, chapterId, language),
                    ),
                )
            },
            materializeDelivery = { binding, _ ->
                if (binding.id == "binding-en") {
                    Result.success(ContentDelivery.Mihon(7L, 70L, 700L))
                } else {
                    Result.success(ContentDelivery.Mihon(8L, 80L, 800L))
                }
            },
        )

        val options = provider.resolve("title", "canonical-chapter-37").getOrThrow()

        options.map { it.language }.shouldContainExactly("en", "pt-BR")
        options.map { it.addonId }.distinct() shouldBe listOf(AddonId("mangadex"))
        chapterRepository.writeCount shouldBe 0
    }

    @Test
    fun `trusted alternative Add-on resolves a missing chapter without prior source mapping`() = runTest {
        val alternative = binding("binding-other", "7:/dandadan").copy(
            matchConfidence = 0.98,
        )
        val chapters = FakeCanonicalChapterRepository(variants = emptyList())
        var materializations = 0
        val provider = MihonContentProvider(
            addonId = AddonId("mangadex"),
            contentBindingRepository = FakeContentBindingRepository(listOf(alternative)),
            canonicalChapterRepository = chapters,
            parser = ParseCanonicalChapterLabel(),
            fetchInventory = {
                Result.success(inventory(it.id, 7L, "en", snapshot(7L, it.id, "/chapter-37", "en")))
            },
            materializeDelivery = { _, _ ->
                materializations++
                Result.success(ContentDelivery.Mihon(7L, 99L, 123L))
            },
        )

        val choices = provider.resolve("title", "canonical-chapter-37").getOrThrow()

        choices.single().delivery shouldBe ContentDelivery.Mihon(7L, 99L, 123L)
        materializations shouldBe 1
        chapters.writeCount shouldBe 0
    }

    @Test
    fun `unmapped alternative never offers different chapter number`() = runTest {
        val alternative = binding("binding-other", "7:/dandadan")
        var materializations = 0
        val provider = MihonContentProvider(
            addonId = AddonId("mangadex"),
            contentBindingRepository = FakeContentBindingRepository(listOf(alternative)),
            canonicalChapterRepository = FakeCanonicalChapterRepository(variants = emptyList()),
            parser = ParseCanonicalChapterLabel(),
            fetchInventory = {
                Result.success(
                    inventory(
                        it.id,
                        7L,
                        "en",
                        snapshot(7L, it.id, "/chapter-126", "en").copy(
                            rawName = "Chapter 126",
                            rawNumberHint = 126.0,
                        ),
                    ),
                )
            },
            materializeDelivery = { _, _ ->
                materializations++
                Result.success(ContentDelivery.Mihon(7L, 99L, 126L))
            },
        )

        provider.resolve("title", "canonical-chapter-37").getOrThrow() shouldBe emptyList()
        materializations shouldBe 0
    }

    @Test
    fun `ambiguous title binding cannot use unmapped numeric fallback`() = runTest {
        val ambiguous = binding("binding-ambiguous", "7:/dandadan").copy(matchConfidence = 0.80)
        var fetches = 0
        val provider = MihonContentProvider(
            addonId = AddonId("mangadex"),
            contentBindingRepository = FakeContentBindingRepository(listOf(ambiguous)),
            canonicalChapterRepository = FakeCanonicalChapterRepository(variants = emptyList()),
            parser = ParseCanonicalChapterLabel(),
            fetchInventory = {
                fetches++
                Result.success(inventory(it.id, 7L, "en", snapshot(7L, it.id, "/chapter-37", "en")))
            },
            materializeDelivery = { _, _ ->
                Result.success(ContentDelivery.Mihon(7L, 99L, 123L))
            },
        )

        provider.resolve("title", "canonical-chapter-37").getOrThrow() shouldBe emptyList()
        fetches shouldBe 0
    }

    @Test
    fun `download materialization failure is not reported as missing chapter`() = runTest {
        val alternative = binding("binding-other", "7:/dandadan")
        val provider = MihonContentProvider(
            addonId = AddonId("mangadex"),
            contentBindingRepository = FakeContentBindingRepository(listOf(alternative)),
            canonicalChapterRepository = FakeCanonicalChapterRepository(variants = emptyList()),
            parser = ParseCanonicalChapterLabel(),
            fetchInventory = {
                Result.success(inventory(it.id, 7L, "en", snapshot(7L, it.id, "/chapter-37", "en")))
            },
            materializeDelivery = { _, _ -> Result.failure(IllegalStateException("storage unavailable")) },
        )

        provider.resolve("title", "canonical-chapter-37").exceptionOrNull()?.message shouldBe
            "storage unavailable"
    }

    @Test
    fun `one crashing internal source does not hide matching chapters from another language`() = runTest {
        val en = binding(id = "binding-en", sourceKey = "7:/dandadan")
        val pt = binding(id = "binding-pt", sourceKey = "8:/dandadan")
        val provider = MihonContentProvider(
            addonId = AddonId("mangadex"),
            contentBindingRepository = FakeContentBindingRepository(listOf(en, pt)),
            canonicalChapterRepository = FakeCanonicalChapterRepository(variants = emptyList()),
            parser = ParseCanonicalChapterLabel(),
            fetchInventory = { item ->
                if (item.id == en.id) error("English source offline")
                Result.success(
                    inventory(
                        bindingId = item.id,
                        sourceId = 8L,
                        language = "pt-BR",
                        snapshot = snapshot(8L, item.id, "/chapter-37", "pt-BR"),
                    ),
                )
            },
            materializeDelivery = { _, _ ->
                Result.success(ContentDelivery.Mihon(8L, 80L, 800L))
            },
        )

        val result = provider.resolve("title", "canonical-chapter-37").getOrThrow()
        result.single().language shouldBe "pt-BR"
    }

    @Test
    fun `partial failed inventory without a matching alternative is retryable not missing`() = runTest {
        val en = binding(id = "binding-en", sourceKey = "7:/dandadan")
        val pt = binding(id = "binding-pt", sourceKey = "8:/dandadan")
        val provider = MihonContentProvider(
            addonId = AddonId("mangadex"),
            contentBindingRepository = FakeContentBindingRepository(listOf(en, pt)),
            canonicalChapterRepository = FakeCanonicalChapterRepository(variants = emptyList()),
            parser = ParseCanonicalChapterLabel(),
            fetchInventory = { item ->
                if (item.id == en.id) error("English source offline")
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
            materializeDelivery = { _, _ ->
                Result.success(ContentDelivery.Mihon(8L, 80L, 800L))
            },
        )

        provider.resolve("title", "canonical-chapter-37")
            .exceptionOrNull()?.message shouldBe "English source offline"
    }

    @Test
    fun `count-only chapter resolves an exact release from trusted bound Add-on`() = runTest {
        val linked = binding(id = "binding-pt", sourceKey = "7:/death-note")
        val placeholder = CanonicalChapter(
            id = inferredChapterId("title", 37),
            canonicalTitleId = "title",
            displayNumber = "37",
            type = CanonicalChapterType.REGULAR,
            baseNumber = 37,
            confidence = 0.0,
        )
        val provider = MihonContentProvider(
            addonId = AddonId("mangadex"),
            contentBindingRepository = FakeContentBindingRepository(listOf(linked)),
            canonicalChapterRepository = FakeCanonicalChapterRepository(emptyList(), placeholder),
            parser = ParseCanonicalChapterLabel(),
            fetchInventory = {
                Result.success(inventory(it.id, 7L, "en", snapshot(7L, it.id, "/chapter-37", "en")))
            },
            materializeDelivery = { _, _ ->
                Result.success(ContentDelivery.Mihon(7L, 99L, 370L))
            },
        )

        val available = provider.resolve("title", placeholder.id).getOrThrow()
        available.single().delivery shouldBe ContentDelivery.Mihon(7L, 99L, 370L)
    }

    @Test
    fun `count-only number never resolves to mismatched upstream chapter`() = runTest {
        val linked = binding(id = "binding-pt", sourceKey = "7:/death-note")
        val placeholder = CanonicalChapter(
            id = inferredChapterId("title", 37),
            canonicalTitleId = "title",
            displayNumber = "37",
            type = CanonicalChapterType.REGULAR,
            baseNumber = 37,
            confidence = 0.0,
        )
        val provider = MihonContentProvider(
            addonId = AddonId("mangadex"),
            contentBindingRepository = FakeContentBindingRepository(listOf(linked)),
            canonicalChapterRepository = FakeCanonicalChapterRepository(emptyList(), placeholder),
            parser = ParseCanonicalChapterLabel(),
            fetchInventory = {
                Result.success(
                    inventory(
                        it.id,
                        7L,
                        "en",
                        snapshot(7L, it.id, "/chapter-138", "en").copy(
                            rawName = "Chapter 138",
                            rawNumberHint = 138.0,
                        ),
                    ),
                )
            },
            materializeDelivery = { _, _ -> error("must not materialize a mismatched chapter") },
        )

        provider.resolve("title", placeholder.id).getOrThrow() shouldBe emptyList()
    }

    @Test
    fun `stale mapping never offers a release with a different current chapter identity`() = runTest {
        val binding = binding(id = "binding-en", sourceKey = "7:/aot")
        val chapterRepository = FakeCanonicalChapterRepository(
            variants = listOf(
                ChapterVariant(
                    id = "variant-en",
                    canonicalChapterId = "canonical-chapter-4",
                    sourceId = 7L,
                    sourceChapterId = "/stale-key",
                    sourceChapterUrl = "/stale-key",
                    rawName = "Chapter 4",
                ),
            ),
            chapter = CanonicalChapter(
                id = "canonical-chapter-4",
                canonicalTitleId = "title",
                displayNumber = "4",
                type = CanonicalChapterType.REGULAR,
                baseNumber = 4,
                confidence = 1.0,
            ),
        )
        val provider = MihonContentProvider(
            addonId = AddonId("mangadex"),
            contentBindingRepository = FakeContentBindingRepository(listOf(binding)),
            canonicalChapterRepository = chapterRepository,
            parser = ParseCanonicalChapterLabel(),
            fetchInventory = {
                Result.success(
                    inventory(
                        bindingId = it.id,
                        sourceId = 7L,
                        language = "en",
                        snapshot = SourceChapterSnapshot(
                            sourceId = 7L,
                            sourceMappingId = it.id,
                            sourceChapterId = "/stale-key",
                            sourceChapterUrl = "/stale-key",
                            rawName = "Chapter 126",
                            language = "en",
                            rawNumberHint = 126.0,
                        ),
                    ),
                )
            },
            materializeDelivery = { _, _ ->
                Result.success(ContentDelivery.Mihon(7L, 70L, 1260L))
            },
        )

        provider.resolve("title", "canonical-chapter-4").getOrThrow() shouldBe emptyList()
    }

    private fun binding(id: String, sourceKey: String) = ContentBinding(
        id = id,
        canonicalTitleId = "title",
        addonId = AddonId("mangadex"),
        providerTitleKey = sourceKey,
        matchConfidence = 1.0,
        verifiedByUser = false,
        availability = ContentBindingAvailability.AVAILABLE,
        runtimePayload = byteArrayOf(1),
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun variant(sourceId: Long, sourceChapterId: String) = ChapterVariant(
        id = "variant-$sourceId",
        canonicalChapterId = "canonical-chapter-37",
        sourceId = sourceId,
        sourceChapterId = sourceChapterId,
        sourceChapterUrl = sourceChapterId,
        rawName = "Chapter 37",
    )

    private fun inventory(
        bindingId: String,
        sourceId: Long,
        language: String,
        snapshot: SourceChapterSnapshot,
    ) = SourceChapterInventory(
        sourceMappingId = bindingId,
        sourceId = sourceId,
        canonicalTitleId = "title",
        chapters = listOf(snapshot),
        mihonMangaId = 99L,
        language = language,
    )

    private fun snapshot(
        sourceId: Long,
        bindingId: String,
        sourceChapterId: String,
        language: String,
    ) = SourceChapterSnapshot(
        sourceId = sourceId,
        sourceMappingId = bindingId,
        sourceChapterId = sourceChapterId,
        sourceChapterUrl = sourceChapterId,
        rawName = "Chapter 37",
        language = language,
        scanlationGroup = "Group",
        releaseDate = 37L,
        rawNumberHint = 37.0,
    )

    private class RecordingDiagnostics : ChapterInventoryDiagnostics {
        val events = mutableListOf<ChapterInventoryDiagnosticEvent>()
        private var active = false
        override fun start(canonicalTitleId: String): String { active = true; return "test" }
        override fun stop() { active = false }
        override fun clear() { active = false; events.clear() }
        override fun isRecording(canonicalTitleId: String): Boolean = active && canonicalTitleId == "title"
        override fun record(event: ChapterInventoryDiagnosticEvent) { if (active) events += event }
        override fun report(): String = ""
    }

    private class FakeContentBindingRepository(
        private val bindings: List<ContentBinding>,
    ) : ContentBindingRepository {
        override suspend fun get(canonicalTitleId: String, addonId: AddonId): ContentBinding? =
            bindings.lastOrNull { it.canonicalTitleId == canonicalTitleId && it.addonId == addonId }

        override suspend fun getByTitle(canonicalTitleId: String): List<ContentBinding> =
            bindings.filter { it.canonicalTitleId == canonicalTitleId }

        override suspend fun upsert(binding: ContentBinding) = error("provider must not write bindings")
        override suspend fun markUnavailable(bindingId: String, updatedAt: Long) = error("provider must not write")
    }

    private class FakeChapterEvidenceRepository(
        private val evidence: List<PersistedChapterEvidence>,
    ) : ChapterEvidenceRepository {
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<PersistedChapterEvidence> =
            evidence.filter { it.evidence.canonicalTitleId == canonicalTitleId }

        override suspend fun getByProducerExternalKey(
            producerKind: ProducerKind,
            producerId: String,
            externalChapterKey: String,
        ): PersistedChapterEvidence? = evidence.firstOrNull {
            it.evidence.producerKind == producerKind &&
                it.evidence.producerId == producerId &&
                it.evidence.externalChapterKey == externalChapterKey
        }

        override suspend fun upsert(
            evidence: ChapterEvidence,
            mappedCanonicalChapterId: String?,
        ): PersistedChapterEvidence = PersistedChapterEvidence(evidence, mappedCanonicalChapterId)
    }

    private class FakeCanonicalChapterRepository(
        private val variants: List<ChapterVariant>,
        private val chapter: CanonicalChapter = CanonicalChapter(
            id = "canonical-chapter-37",
            canonicalTitleId = "title",
            displayNumber = "37",
            type = CanonicalChapterType.REGULAR,
            baseNumber = 37,
            confidence = 1.0,
        ),
    ) : CanonicalChapterRepository {
        var writeCount = 0

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapter> = emptyList()
        override fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> = emptyFlow()
        override suspend fun getById(id: String): CanonicalChapter? = chapter.takeIf { it.id == id }
        override suspend fun getVariantBySourceIdentity(sourceId: Long, sourceChapterId: String): ChapterVariant? =
            variants.firstOrNull { it.sourceId == sourceId && it.sourceChapterId == sourceChapterId }

        override suspend fun getVariantsByCanonicalChapterId(canonicalChapterId: String): List<ChapterVariant> =
            variants.filter { it.canonicalChapterId == canonicalChapterId }

        override suspend fun getVariantsBySourceMappingId(sourceMappingId: String): List<ChapterVariant> = emptyList()

        override suspend fun upsert(chapter: CanonicalChapter) {
            writeCount++
        }

        override suspend fun upsertVariant(variant: ChapterVariant) {
            writeCount++
        }

        override suspend fun upsertBatch(chapters: List<CanonicalChapter>, variants: List<ChapterVariant>) {
            writeCount++
        }
    }
}
