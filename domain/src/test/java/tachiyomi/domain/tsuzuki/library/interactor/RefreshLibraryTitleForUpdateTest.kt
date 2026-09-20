package tachiyomi.domain.tsuzuki.library.interactor

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.interactor.ReconcileChapterInventory
import tachiyomi.domain.tsuzuki.chapter.interactor.RefreshCanonicalChapters
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterInventory
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterSnapshot
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.chapter.service.ChapterInventoryGateway
import tachiyomi.domain.tsuzuki.library.model.LibraryTitle
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository

class RefreshLibraryTitleForUpdateTest {

    @Test
    fun `refreshes every eligible representation in one canonical batch`() = runTest {
        val mappings = listOf(
            mapping("one", 1L, 11L, SourceMappingAvailability.AVAILABLE),
            mapping("two", 2L, 22L, SourceMappingAvailability.UNKNOWN),
            mapping("three", 3L, null, SourceMappingAvailability.AVAILABLE),
            mapping("four", 4L, 44L, SourceMappingAvailability.UNAVAILABLE),
        )
        val repository = FakeCanonicalChapterRepository()
        val gateway = FakeGateway(
            mapOf(
                "one" to inventory("one", 1L, "Chapter 1"),
                "two" to inventory("two", 2L, "Chapter 2"),
            ),
        )
        val refresh = RefreshCanonicalChapters(
            sourceTitleMappingRepository = FakeMappings(mappings),
            chapterInventoryGateway = gateway,
            reconcileChapterInventory = ReconcileChapterInventory(
                parser = ParseCanonicalChapterLabel(),
                canonicalChapterRepository = repository,
                idFactory = { "chapter-" + (repository.createdIds.size + 1).also(repository.createdIds::add) },
                variantIdFactory = { "variant-" + (repository.variantIds.size + 1).also(repository.variantIds::add) },
                clock = { 100L },
            ),
        )

        val result = RefreshLibraryTitleForUpdate(refresh).execute(libraryTitle(mappings)).getOrThrow()

        result?.sourceMappingIds shouldBe setOf("one", "two")
        gateway.requested shouldContainExactly listOf("one", "two")
        repository.batchCalls shouldBe 1
    }

    @Test
    fun `title without eligible materialized representation is skipped without failure`() = runTest {
        val mappings = listOf(
            mapping("one", 1L, null, SourceMappingAvailability.AVAILABLE),
            mapping("two", 2L, 22L, SourceMappingAvailability.UNAVAILABLE),
        )
        val repository = FakeCanonicalChapterRepository()
        val gateway = FakeGateway(emptyMap())
        val refresh = RefreshCanonicalChapters(
            sourceTitleMappingRepository = FakeMappings(mappings),
            chapterInventoryGateway = gateway,
            reconcileChapterInventory = ReconcileChapterInventory(
                parser = ParseCanonicalChapterLabel(),
                canonicalChapterRepository = repository,
                idFactory = { "chapter" },
                variantIdFactory = { "variant" },
                clock = { 100L },
            ),
        )

        val result = RefreshLibraryTitleForUpdate(refresh).execute(libraryTitle(mappings))

        result.isSuccess shouldBe true
        result.getOrThrow() shouldBe null
        gateway.requested shouldBe emptyList()
        repository.batchCalls shouldBe 0
    }

    private fun libraryTitle(mappings: List<SourceTitleMapping>) = LibraryTitle(
        title = CanonicalTitle("title-1", "Title", CanonicalIdentityState.RESOLVED, 100L, 100L),
        entry = CanonicalLibraryEntry("title-1", LibraryStatus.READING, true, 100L, 100L),
        sources = mappings,
    )

    private fun mapping(
        id: String,
        sourceId: Long,
        mihonMangaId: Long?,
        availability: SourceMappingAvailability,
    ) = SourceTitleMapping(
        id = id,
        canonicalTitleId = "title-1",
        mihonMangaId = mihonMangaId,
        sourceId = sourceId,
        sourceUrl = "/$id",
        language = "en",
        matchConfidence = 1.0,
        verifiedByUser = true,
        availability = availability,
        preferredOverride = false,
        createdAt = 100L,
        updatedAt = 100L,
    )

    private fun inventory(mappingId: String, sourceId: Long, name: String) = SourceChapterInventory(
        sourceMappingId = mappingId,
        sourceId = sourceId,
        canonicalTitleId = "title-1",
        chapters = listOf(
            SourceChapterSnapshot(
                sourceId = sourceId,
                sourceMappingId = mappingId,
                sourceChapterId = "/$mappingId/chapter",
                sourceChapterUrl = "/$mappingId/chapter",
                rawName = name,
                language = "en",
            ),
        ),
    )

    private class FakeGateway(
        private val inventories: Map<String, SourceChapterInventory>,
    ) : ChapterInventoryGateway {
        val requested = mutableListOf<String>()

        override suspend fun fetch(mapping: SourceTitleMapping): Result<SourceChapterInventory> {
            requested += mapping.id
            return inventories[mapping.id]?.let(Result.Companion::success)
                ?: Result.failure(IllegalStateException("missing inventory"))
        }
    }

    private class FakeMappings(
        private val mappings: List<SourceTitleMapping>,
    ) : SourceTitleMappingRepository {
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<SourceTitleMapping> =
            mappings.filter { it.canonicalTitleId == canonicalTitleId }

        override fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<SourceTitleMapping>> =
            MutableStateFlow(mappings.filter { it.canonicalTitleId == canonicalTitleId })

        override suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping? =
            mappings.firstOrNull { it.sourceId == sourceId && it.sourceUrl == sourceUrl }

        override suspend fun upsert(mapping: SourceTitleMapping) = Unit

        override suspend fun setPreferredForTitle(canonicalTitleId: String, mappingId: String?, updatedAt: Long) = Unit
    }

    private class FakeCanonicalChapterRepository : CanonicalChapterRepository {
        val chapters = linkedMapOf<String, CanonicalChapter>()
        val variants = linkedMapOf<String, ChapterVariant>()
        val createdIds = mutableListOf<Int>()
        val variantIds = mutableListOf<Int>()
        var batchCalls = 0

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
            batchCalls += 1
            chapters.forEach { this.chapters[it.id] = it }
            variants.forEach { this.variants[it.id] = it }
        }
    }
}
