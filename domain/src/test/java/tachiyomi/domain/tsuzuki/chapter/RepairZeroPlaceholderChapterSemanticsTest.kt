package tachiyomi.domain.tsuzuki.chapter

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.interactor.RepairZeroPlaceholderChapterSemantics
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistory
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistoryUpdate
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository

class RepairZeroPlaceholderChapterSemanticsTest {

    @Test
    fun `progress bound semantic variant keeps canonical id while regular zero is split out`() = runTest {
        val repository = FakeCanonicalChapterRepository(
            chapters = listOf(zeroChapter()),
            variants = listOf(regularZeroVariant(), oneShotVariant()),
        )
        val reading = FakeCanonicalReadingRepository(
            progress = CanonicalChapterProgress(
                canonicalChapterId = "chapter-0",
                read = false,
                lastPageRead = 4L,
                lastVariantId = "variant-oneshot",
                updatedAt = 150L,
            ),
        )
        val repair = repair(repository, reading)

        repair.execute("title-1")

        repository.getById("chapter-0")?.type shouldBe CanonicalChapterType.ONESHOT
        repository.getById("chapter-0")?.baseNumber shouldBe null
        repository.getVariantsByCanonicalChapterId("chapter-0")
            .map { it.id } shouldContainExactly listOf("variant-oneshot")

        val regular = repository.getByCanonicalTitleId("title-1")
            .single { it.id != "chapter-0" }
        regular.type shouldBe CanonicalChapterType.REGULAR
        regular.baseNumber shouldBe 0
        repository.getVariantsByCanonicalChapterId(regular.id)
            .map { it.id } shouldContainExactly listOf("variant-regular")

        reading.getProgress("chapter-0")?.lastVariantId shouldBe "variant-oneshot"
        reading.getProgress(regular.id) shouldBe null
    }

    @Test
    fun `without canonical state regular zero keeps id and semantic placeholder is split out`() = runTest {
        val repository = FakeCanonicalChapterRepository(
            chapters = listOf(zeroChapter()),
            variants = listOf(regularZeroVariant(), oneShotVariant()),
        )
        val reading = FakeCanonicalReadingRepository()
        val repair = repair(repository, reading)

        repair.execute("title-1")

        repository.getById("chapter-0")?.type shouldBe CanonicalChapterType.REGULAR
        repository.getById("chapter-0")?.baseNumber shouldBe 0
        repository.getVariantsByCanonicalChapterId("chapter-0")
            .map { it.id } shouldContainExactly listOf("variant-regular")

        val oneShot = repository.getByCanonicalTitleId("title-1")
            .single { it.id != "chapter-0" }
        oneShot.type shouldBe CanonicalChapterType.ONESHOT
        oneShot.baseNumber shouldBe null
        repository.getVariantsByCanonicalChapterId(oneShot.id)
            .map { it.id } shouldContainExactly listOf("variant-oneshot")
    }

    @Test
    fun `single semantic placeholder is reclassified in place`() = runTest {
        val repository = FakeCanonicalChapterRepository(
            chapters = listOf(zeroChapter()),
            variants = listOf(oneShotVariant()),
        )
        val repair = repair(repository, FakeCanonicalReadingRepository())

        repair.execute("title-1")

        repository.getByCanonicalTitleId("title-1").map { it.id } shouldContainExactly listOf("chapter-0")
        repository.getById("chapter-0")?.type shouldBe CanonicalChapterType.ONESHOT
        repository.getVariantsByCanonicalChapterId("chapter-0")
            .map { it.id } shouldContainExactly listOf("variant-oneshot")
    }

    private fun repair(
        repository: FakeCanonicalChapterRepository,
        reading: FakeCanonicalReadingRepository,
    ) = RepairZeroPlaceholderChapterSemantics(
        parser = ParseCanonicalChapterLabel(),
        canonicalChapterRepository = repository,
        canonicalReadingRepository = reading,
        idFactory = object : () -> String {
            private var next = 0
            override fun invoke(): String = "chapter-repaired-${++next}"
        },
        clock = { 200L },
    )

    private fun zeroChapter() = CanonicalChapter(
        id = "chapter-0",
        canonicalTitleId = "title-1",
        displayNumber = "0",
        type = CanonicalChapterType.REGULAR,
        baseNumber = 0,
        confidence = 1.0,
        createdAt = 100L,
        updatedAt = 100L,
    )

    private fun regularZeroVariant() = ChapterVariant(
        id = "variant-regular",
        canonicalChapterId = "chapter-0",
        sourceMappingId = "mapping-1",
        sourceId = 1L,
        sourceChapterId = "/volume-20",
        sourceChapterUrl = "/volume-20",
        language = "en",
        rawName = "Ch. 0 - Volume 20",
        createdAt = 100L,
        updatedAt = 100L,
    )

    private fun oneShotVariant() = ChapterVariant(
        id = "variant-oneshot",
        canonicalChapterId = "chapter-0",
        sourceMappingId = "mapping-1",
        sourceId = 1L,
        sourceChapterId = "/oneshot",
        sourceChapterUrl = "/oneshot",
        language = "en",
        rawName = "Ch. 0 - Oneshot",
        createdAt = 100L,
        updatedAt = 100L,
    )

    private class FakeCanonicalChapterRepository(
        chapters: List<CanonicalChapter>,
        variants: List<ChapterVariant>,
    ) : CanonicalChapterRepository {
        private val chapters = chapters.associateBy { it.id }.toMutableMap()
        private val variants = variants.associateBy { it.id }.toMutableMap()

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapter> =
            chapters.values
                .filter { it.canonicalTitleId == canonicalTitleId }
                .sortedBy { it.sortKey }

        override fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> =
            MutableStateFlow(chapters.values.filter { it.canonicalTitleId == canonicalTitleId })

        override suspend fun getById(id: String): CanonicalChapter? = chapters[id]

        override suspend fun getVariantBySourceIdentity(
            sourceId: Long,
            sourceChapterId: String,
        ): ChapterVariant? = variants.values.firstOrNull {
            it.sourceId == sourceId && it.sourceChapterId == sourceChapterId
        }

        override suspend fun getVariantsByCanonicalChapterId(canonicalChapterId: String): List<ChapterVariant> =
            variants.values
                .filter { it.canonicalChapterId == canonicalChapterId }
                .sortedBy { it.id }

        override suspend fun getVariantsBySourceMappingId(sourceMappingId: String): List<ChapterVariant> =
            variants.values.filter { it.sourceMappingId == sourceMappingId }

        override suspend fun upsert(chapter: CanonicalChapter) {
            chapters[chapter.id] = chapter
        }

        override suspend fun upsertVariant(variant: ChapterVariant) {
            variants[variant.id] = variant
        }

        override suspend fun upsertBatch(
            chapters: List<CanonicalChapter>,
            variants: List<ChapterVariant>,
        ) {
            chapters.forEach { this.chapters[it.id] = it }
            variants.forEach { this.variants[it.id] = it }
        }
    }

    private class FakeCanonicalReadingRepository(
        private val progress: CanonicalChapterProgress? = null,
        private val history: CanonicalChapterHistory? = null,
    ) : CanonicalReadingRepository {
        override suspend fun getProgress(canonicalChapterId: String): CanonicalChapterProgress? =
            progress?.takeIf { it.canonicalChapterId == canonicalChapterId }

        override fun observeProgress(canonicalChapterId: String): Flow<CanonicalChapterProgress?> =
            MutableStateFlow(progress?.takeIf { it.canonicalChapterId == canonicalChapterId })

        override suspend fun getProgressByCanonicalTitleId(
            canonicalTitleId: String,
        ): List<CanonicalChapterProgress> = listOfNotNull(progress)

        override suspend fun upsertProgress(progress: CanonicalChapterProgress) = Unit

        override suspend fun getHistory(canonicalChapterId: String): CanonicalChapterHistory? =
            history?.takeIf { it.canonicalChapterId == canonicalChapterId }

        override suspend fun recordHistory(update: CanonicalChapterHistoryUpdate) = Unit

        override suspend fun recordCheckpoint(
            progress: CanonicalChapterProgress,
            history: CanonicalChapterHistoryUpdate?,
        ) = Unit
    }
}
