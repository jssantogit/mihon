package tachiyomi.domain.tsuzuki.chapter

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.interactor.ReconcileChapterInventory
import tachiyomi.domain.tsuzuki.chapter.interactor.RefreshCanonicalChapters
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterInventory
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterSnapshot
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.chapter.service.ChapterInventoryGateway
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository

class ChapterInventoryAndReconciliationTest {

    @Test
    fun `same specific identity from two mappings shares one canonical chapter and keeps both variants`() = runTest {
        val repository = FakeCanonicalChapterRepository()
        val reconciler = reconciler(repository)

        val first = reconciler.execute(inventory(mappingId = "mapping-1", sourceId = 1L, name = "Chapter 12"))
        val second = reconciler.execute(inventory(mappingId = "mapping-2", sourceId = 2L, name = "Ch. 012"))

        first.canonicalChapters.map { it.id } shouldBe listOf("chapter-1")
        second.canonicalChapters.map { it.id } shouldBe listOf("chapter-1")
        repository.getByCanonicalTitleId("title-1").size shouldBe 1
        repository.getVariantsByCanonicalChapterId("chapter-1")
            .map { it.sourceMappingId } shouldContainExactly listOf("mapping-1", "mapping-2")
    }

    @Test
    fun `multiple inventories reconcile through one atomic batch and share new canonical identities`() = runTest {
        val repository = FakeCanonicalChapterRepository()
        val reconciler = reconciler(repository)

        val report = reconciler.execute(
            listOf(
                inventory(mappingId = "mapping-1", sourceId = 1L, name = "Chapter 12", sourceChapterId = "/one"),
                inventory(mappingId = "mapping-2", sourceId = 2L, name = "Ch. 012", sourceChapterId = "/two"),
            ),
        )

        report.canonicalChapters.map { it.id } shouldBe listOf("chapter-1")
        report.variants.map { it.sourceMappingId } shouldContainExactly listOf("mapping-1", "mapping-2")
        report.sourceMappingIds shouldBe setOf("mapping-1", "mapping-2")
        repository.upsertBatchCalls shouldBe 1
        repository.getByCanonicalTitleId("title-1").size shouldBe 1
    }

    @Test
    fun `mangadex volume prefix and plain chapter share one identity without losing variants`() = runTest {
        val repository = FakeCanonicalChapterRepository()
        val reconciler = reconciler(repository)

        reconciler.execute(inventory("mapping-1", 1L, "Vol.1 Ch.1 - Um Soco", "/md/1"))
        reconciler.execute(inventory("mapping-2", 2L, "Chapter 1", "/mf/1"))

        val chapters = repository.getByCanonicalTitleId("title-1")
        chapters.size shouldBe 1
        chapters.single().baseNumber shouldBe 1
        repository.getVariantsByCanonicalChapterId(chapters.single().id).size shouldBe 2
    }

    @Test
    fun `incompatible type part suffix and numbered semantic labels stay separate`() = runTest {
        val repository = FakeCanonicalChapterRepository()
        val reconciler = reconciler(repository)

        reconciler.execute(inventory("mapping-1", 1L, "Chapter 12"))
        reconciler.execute(inventory("mapping-2", 2L, "12.5"))
        reconciler.execute(inventory("mapping-3", 3L, "12a"))
        reconciler.execute(inventory("mapping-4", 4L, "Extra 12"))
        reconciler.execute(inventory("mapping-5", 5L, "Prologue 1"))
        reconciler.execute(inventory("mapping-6", 6L, "Prologue 2"))

        repository.getByCanonicalTitleId("title-1").map { it.identity } shouldContainExactly listOf(
            repository.getByCanonicalTitleId("title-1")[0].identity,
            repository.getByCanonicalTitleId("title-1")[1].identity,
            repository.getByCanonicalTitleId("title-1")[2].identity,
            repository.getByCanonicalTitleId("title-1")[3].identity,
            repository.getByCanonicalTitleId("title-1")[4].identity,
            repository.getByCanonicalTitleId("title-1")[5].identity,
        )
        repository.getByCanonicalTitleId("title-1").size shouldBe 6
    }

    @Test
    fun `unknown labels from different sources are not heuristically merged`() = runTest {
        val repository = FakeCanonicalChapterRepository()
        val reconciler = reconciler(repository)

        reconciler.execute(inventory("mapping-1", 1L, "Bonus chapter", sourceChapterId = "/one"))
        reconciler.execute(inventory("mapping-2", 2L, "Bonus chapter", sourceChapterId = "/two"))

        repository.getByCanonicalTitleId("title-1").size shouldBe 2
    }

    @Test
    fun `existing source identity association wins over later parsed identity`() = runTest {
        val repository = FakeCanonicalChapterRepository()
        val reconciler = reconciler(repository)

        reconciler.execute(inventory("mapping-1", 1L, "Chapter 12", sourceChapterId = "/chapter"))
        reconciler.execute(inventory("mapping-1", 1L, "Chapter 99", sourceChapterId = "/chapter"))

        repository.getByCanonicalTitleId("title-1").size shouldBe 1
        repository.getVariantBySourceIdentity(1L, "/chapter")?.canonicalChapterId shouldBe "chapter-1"
        repository.getById("chapter-1")?.baseNumber shouldBe 12
    }

    @Test
    fun `refresh never deletes variants absent from the current source inventory`() = runTest {
        val repository = FakeCanonicalChapterRepository()
        val reconciler = reconciler(repository)

        reconciler.execute(
            SourceChapterInventory(
                sourceMappingId = "mapping-1",
                sourceId = 1L,
                canonicalTitleId = "title-1",
                chapters = listOf(
                    snapshot(1L, "mapping-1", "Chapter 1", "/one"),
                    snapshot(1L, "mapping-1", "Chapter 2", "/two"),
                ),
            ),
        )
        reconciler.execute(
            SourceChapterInventory(
                sourceMappingId = "mapping-1",
                sourceId = 1L,
                canonicalTitleId = "title-1",
                chapters = listOf(snapshot(1L, "mapping-1", "Chapter 1", "/one")),
            ),
        )

        repository.getVariantBySourceIdentity(1L, "/two") shouldBe ChapterVariant(
            id = "variant-2",
            canonicalChapterId = "chapter-2",
            sourceMappingId = "mapping-1",
            sourceId = 1L,
            sourceChapterId = "/two",
            sourceChapterUrl = "/two",
            rawName = "Chapter 2",
            language = "en",
            createdAt = 100L,
            updatedAt = 100L,
        )
    }

    @Test
    fun `reconciling one mapping does not alter another mapping variants`() = runTest {
        val repository = FakeCanonicalChapterRepository()
        val reconciler = reconciler(repository)

        reconciler.execute(inventory("mapping-1", 1L, "Chapter 1", "/one"))
        reconciler.execute(inventory("mapping-2", 2L, "Chapter 1", "/two"))
        val mappingTwoBefore = repository.getVariantBySourceIdentity(2L, "/two")

        reconciler.execute(inventory("mapping-1", 1L, "Chapter 9", "/one"))

        repository.getVariantBySourceIdentity(2L, "/two") shouldBe mappingTwoBefore
        repository.getVariantsBySourceMappingId("mapping-2").size shouldBe 1
    }

    @Test
    fun `transactional batch failure leaves canonical state without partial writes`() = runTest {
        val repository = FailingTransactionalCanonicalChapterRepository()
        val reconciler = reconciler(repository)
        repository.failBatch = true

        shouldThrow<IllegalStateException> {
            reconciler.execute(inventory("mapping-1", 1L, "Chapter 1", "/one"))
        }

        repository.chapters shouldBe emptyMap()
        repository.variants shouldBe emptyMap()
    }

    @Test
    fun `multi mapping refresh batch failure leaves every mapping unpersisted`() = runTest {
        val repository = FailingTransactionalCanonicalChapterRepository()
        val gateway = FakeChapterInventoryGateway()
        val mappings = FakeSourceTitleMappingRepository(
            mapping("mapping-1", materialized = true),
            mapping("mapping-2", materialized = true),
        )
        gateway.inventories = mapOf(
            "mapping-1" to inventory("mapping-1", 1L, "Chapter 1", "/one"),
            "mapping-2" to inventory("mapping-2", 2L, "Chapter 2", "/two"),
        )
        repository.failBatch = true

        val refresh = RefreshCanonicalChapters(mappings, gateway, reconciler(repository))
        refresh.execute("title-1", mappingIds = listOf("mapping-1", "mapping-2")).isFailure shouldBe true

        repository.upsertBatchCalls shouldBe 1
        repository.chapters shouldBe emptyMap()
        repository.variants shouldBe emptyMap()
    }

    @Test
    fun `source failure leaves persisted state intact and cancellation propagates`() = runTest {
        val repository = FakeCanonicalChapterRepository()
        val gateway = FakeChapterInventoryGateway()
        val refresh = RefreshCanonicalChapters(
            sourceTitleMappingRepository = FakeSourceTitleMappingRepository(mapping("mapping-1", true)),
            chapterInventoryGateway = gateway,
            reconcileChapterInventory = reconciler(repository),
        )

        gateway.result = Result.success(inventory("mapping-1", 1L, "Chapter 1", "/one"))
        refresh.execute("title-1").isSuccess shouldBe true
        val chaptersBeforeFailure = repository.chapters.toMap()
        val variantsBeforeFailure = repository.variants.toMap()

        gateway.result = Result.failure(IllegalStateException("network"))
        refresh.execute("title-1").isFailure shouldBe true
        repository.chapters shouldBe chaptersBeforeFailure
        repository.variants shouldBe variantsBeforeFailure

        gateway.result = Result.failure(CancellationException("cancelled"))
        shouldThrow<CancellationException> { refresh.execute("title-1") }
    }

    @Test
    fun `refresh defaults to one eligible preferred mapping and explicit subset can broaden atomically`() = runTest {
        val repository = FakeCanonicalChapterRepository()
        val gateway = FakeChapterInventoryGateway()
        val mappings = FakeSourceTitleMappingRepository(
            mapping("mapping-1", materialized = true, preferred = false),
            mapping("mapping-2", materialized = true, preferred = true),
            mapping("mapping-3", materialized = true, preferred = false),
        )
        gateway.inventories = mapOf(
            "mapping-1" to inventory("mapping-1", 1L, "Chapter 1"),
            "mapping-2" to inventory("mapping-2", 2L, "Chapter 2"),
            "mapping-3" to inventory("mapping-3", 3L, "Chapter 3"),
        )
        val refresh = RefreshCanonicalChapters(mappings, gateway, reconciler(repository))

        refresh.execute("title-1").getOrThrow().sourceMappingIds shouldBe setOf("mapping-2")
        val callsAfterDefault = repository.upsertBatchCalls

        refresh.execute("title-1", mappingIds = listOf("mapping-1", "mapping-3"))
            .getOrThrow().sourceMappingIds shouldBe setOf("mapping-1", "mapping-3")
        repository.upsertBatchCalls shouldBe callsAfterDefault + 1
    }

    @Test
    fun `refresh skips unavailable mappings and rejects explicitly unavailable mapping`() = runTest {
        val repository = FakeCanonicalChapterRepository()
        val gateway = FakeChapterInventoryGateway()
        val unavailablePreferred = mapping("mapping-1", materialized = true, preferred = true).copy(
            availability = SourceMappingAvailability.UNAVAILABLE,
        )
        val available = mapping("mapping-2", materialized = true)
        val mappings = FakeSourceTitleMappingRepository(unavailablePreferred, available)
        gateway.inventories = mapOf(
            "mapping-2" to inventory("mapping-2", 2L, "Chapter 2"),
        )
        val refresh = RefreshCanonicalChapters(mappings, gateway, reconciler(repository))

        refresh.execute("title-1").getOrThrow().sourceMappingIds shouldBe setOf("mapping-2")
        gateway.requestedMappingIds shouldContainExactly listOf("mapping-2")

        gateway.requestedMappingIds.clear()
        refresh.execute("title-1", mappingId = "mapping-1").isFailure shouldBe true
        gateway.requestedMappingIds shouldBe emptyList()
    }

    @Test
    fun `reconciliation rejects mismatched or blank source identity evidence`() = runTest {
        val repository = FakeCanonicalChapterRepository()
        val reconciler = reconciler(repository)

        shouldThrow<IllegalArgumentException> {
            reconciler.execute(
                inventory("mapping-1", 1L, "Chapter 1").copy(
                    chapters = listOf(snapshot(2L, "mapping-1", "Chapter 1", "/one")),
                ),
            )
        }
        shouldThrow<IllegalArgumentException> {
            reconciler.execute(
                inventory("mapping-1", 1L, "Chapter 1").copy(
                    chapters = listOf(snapshot(1L, "mapping-2", "Chapter 1", "/one")),
                ),
            )
        }
        shouldThrow<IllegalArgumentException> {
            reconciler.execute(
                inventory("mapping-1", 1L, "Chapter 1").copy(
                    chapters = listOf(snapshot(1L, "mapping-1", "Chapter 1", "")),
                ),
            )
        }

        repository.chapters shouldBe emptyMap()
        repository.variants shouldBe emptyMap()
    }

    private fun reconciler(repository: FakeCanonicalChapterRepository) = ReconcileChapterInventory(
        parser = ParseCanonicalChapterLabel(),
        canonicalChapterRepository = repository,
        idFactory = object : () -> String {
            private var next = 0
            override fun invoke(): String = "chapter-${++next}"
        },
        variantIdFactory = object : () -> String {
            private var next = 0
            override fun invoke(): String = "variant-${++next}"
        },
        clock = { 100L },
    )

    private fun inventory(
        mappingId: String,
        sourceId: Long,
        name: String,
        sourceChapterId: String = "/$mappingId/${name.replace(' ', '-')}",
    ) = SourceChapterInventory(
        sourceMappingId = mappingId,
        sourceId = sourceId,
        canonicalTitleId = "title-1",
        chapters = listOf(snapshot(sourceId, mappingId, name, sourceChapterId)),
    )

    private fun snapshot(sourceId: Long, mappingId: String, name: String, url: String) =
        SourceChapterSnapshot(
            sourceId = sourceId,
            sourceMappingId = mappingId,
            sourceChapterId = url,
            sourceChapterUrl = url,
            rawName = name,
            language = "en",
            rawNumberHint = null,
            rawSourceOrder = null,
            scanlationGroup = null,
            releaseDate = null,
            createdAt = 100L,
            updatedAt = 100L,
        )

    private fun mapping(id: String, materialized: Boolean, preferred: Boolean = false) = SourceTitleMapping(
        id = id,
        canonicalTitleId = "title-1",
        mihonMangaId = if (materialized) 10L else null,
        sourceId = id.removePrefix("mapping-").toLong(),
        sourceUrl = "/$id",
        language = "en",
        matchConfidence = 1.0,
        verifiedByUser = false,
        availability = SourceMappingAvailability.AVAILABLE,
        preferredOverride = preferred,
        createdAt = 100L,
        updatedAt = 100L,
    )

    private class FakeChapterInventoryGateway : ChapterInventoryGateway {
        var result: Result<SourceChapterInventory> = Result.success(
            SourceChapterInventory(
                sourceMappingId = "mapping-1",
                sourceId = 1L,
                canonicalTitleId = "title-1",
                chapters = emptyList(),
            ),
        )
        var inventories: Map<String, SourceChapterInventory> = emptyMap()
        val requestedMappingIds = mutableListOf<String>()

        override suspend fun fetch(mapping: SourceTitleMapping): Result<SourceChapterInventory> {
            requestedMappingIds += mapping.id
            return inventories[mapping.id]?.let(Result.Companion::success) ?: result
        }
    }

    private open class FakeCanonicalChapterRepository : CanonicalChapterRepository {
        val chapters = linkedMapOf<String, CanonicalChapter>()
        val variants = linkedMapOf<String, ChapterVariant>()
        var upsertBatchCalls = 0

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapter> =
            chapters.values.filter { it.canonicalTitleId == canonicalTitleId }

        override fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> =
            MutableStateFlow(chapters.values.filter { it.canonicalTitleId == canonicalTitleId })

        override suspend fun getById(id: String): CanonicalChapter? = chapters[id]

        override suspend fun getVariantBySourceIdentity(sourceId: Long, sourceChapterId: String): ChapterVariant? =
            variants.values.firstOrNull { it.sourceId == sourceId && it.sourceChapterId == sourceChapterId }

        override suspend fun getVariantsByCanonicalChapterId(canonicalChapterId: String): List<ChapterVariant> =
            variants.values.filter { it.canonicalChapterId == canonicalChapterId }

        override suspend fun getVariantsBySourceMappingId(sourceMappingId: String): List<ChapterVariant> =
            variants.values.filter { it.sourceMappingId == sourceMappingId }

        override suspend fun upsert(chapter: CanonicalChapter) {
            chapters[chapter.id] = chapter
        }

        override suspend fun upsertVariant(variant: ChapterVariant) {
            variants[variant.id] = variant
        }

        override suspend fun upsertBatch(chapters: List<CanonicalChapter>, variants: List<ChapterVariant>) {
            upsertBatchCalls += 1
            chapters.forEach { upsert(it) }
            variants.forEach { upsertVariant(it) }
        }
    }

    private class FailingTransactionalCanonicalChapterRepository : FakeCanonicalChapterRepository() {
        var failBatch = false

        override suspend fun upsertBatch(chapters: List<CanonicalChapter>, variants: List<ChapterVariant>) {
            val chaptersBefore = this.chapters.toMap()
            val variantsBefore = this.variants.toMap()
            try {
                super.upsertBatch(chapters, variants)
                if (failBatch) throw IllegalStateException("transaction rolled back")
            } catch (error: Throwable) {
                this.chapters.clear()
                this.chapters.putAll(chaptersBefore)
                this.variants.clear()
                this.variants.putAll(variantsBefore)
                throw error
            }
        }
    }

    private class FakeSourceTitleMappingRepository(
        vararg initial: SourceTitleMapping,
    ) : SourceTitleMappingRepository {
        private val mappings = initial.associateBy { it.id }.toMutableMap()

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<SourceTitleMapping> =
            mappings.values.filter { it.canonicalTitleId == canonicalTitleId }

        override fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<SourceTitleMapping>> =
            MutableStateFlow(mappings.values.filter { it.canonicalTitleId == canonicalTitleId })

        override suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping? =
            mappings.values.firstOrNull { it.sourceId == sourceId && it.sourceUrl == sourceUrl }

        override suspend fun upsert(mapping: SourceTitleMapping) {
            mappings[mapping.id] = mapping
        }

        override suspend fun setPreferredForTitle(canonicalTitleId: String, mappingId: String?, updatedAt: Long) =
            Unit
    }
}
