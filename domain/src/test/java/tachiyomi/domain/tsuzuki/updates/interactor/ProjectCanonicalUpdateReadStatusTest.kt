package tachiyomi.domain.tsuzuki.updates.interactor

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistory
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistoryUpdate
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository

class ProjectCanonicalUpdateReadStatusTest {

    @Test
    fun `marking update read projects status onto canonical chapter progress`() = runTest {
        val chapters = FakeCanonicalChapterRepository(
            variant = variant(),
        )
        val reading = FakeCanonicalReadingRepository(
            progress = CanonicalChapterProgress(
                canonicalChapterId = "chapter-1",
                read = false,
                lastPageRead = 7L,
                lastVariantId = null,
                updatedAt = 100L,
            ),
        )
        val interactor = ProjectCanonicalUpdateReadStatus(
            canonicalChapterRepository = chapters,
            canonicalReadingRepository = reading,
            clock = { 500L },
        )

        interactor.execute(sourceId = 7L, sourceChapterId = "/chapter/1", read = true) shouldBe true

        reading.progress shouldBe CanonicalChapterProgress(
            canonicalChapterId = "chapter-1",
            read = true,
            lastPageRead = 7L,
            lastVariantId = "variant-1",
            updatedAt = 500L,
        )
    }

    @Test
    fun `marking update unread clears canonical page progress`() = runTest {
        val chapters = FakeCanonicalChapterRepository(variant())
        val reading = FakeCanonicalReadingRepository(
            progress = CanonicalChapterProgress(
                canonicalChapterId = "chapter-1",
                read = true,
                lastPageRead = 12L,
                lastVariantId = "variant-old",
                updatedAt = 100L,
            ),
        )
        val interactor = ProjectCanonicalUpdateReadStatus(
            canonicalChapterRepository = chapters,
            canonicalReadingRepository = reading,
            clock = { 600L },
        )

        interactor.execute(sourceId = 7L, sourceChapterId = "/chapter/1", read = false) shouldBe true

        reading.progress shouldBe CanonicalChapterProgress(
            canonicalChapterId = "chapter-1",
            read = false,
            lastPageRead = 0L,
            lastVariantId = "variant-1",
            updatedAt = 600L,
        )
    }

    @Test
    fun `unknown source chapter leaves canonical state unchanged`() = runTest {
        val chapters = FakeCanonicalChapterRepository(variant = null)
        val reading = FakeCanonicalReadingRepository(progress = null)
        val interactor = ProjectCanonicalUpdateReadStatus(
            canonicalChapterRepository = chapters,
            canonicalReadingRepository = reading,
            clock = { 700L },
        )

        interactor.execute(sourceId = 7L, sourceChapterId = "/missing", read = true) shouldBe false
        reading.progress shouldBe null
    }

    private fun variant() = ChapterVariant(
        id = "variant-1",
        canonicalChapterId = "chapter-1",
        sourceMappingId = "mapping-1",
        sourceId = 7L,
        sourceChapterId = "/chapter/1",
        sourceChapterUrl = "/chapter/1",
        language = "en",
        rawName = "Chapter 1",
    )

    private class FakeCanonicalChapterRepository(
        private val variant: ChapterVariant?,
    ) : CanonicalChapterRepository {
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapter> = emptyList()
        override fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> =
            MutableStateFlow(emptyList())
        override suspend fun getById(id: String): CanonicalChapter? = null
        override suspend fun getVariantBySourceIdentity(sourceId: Long, sourceChapterId: String): ChapterVariant? =
            variant?.takeIf { it.sourceId == sourceId && it.sourceChapterId == sourceChapterId }
        override suspend fun getVariantsByCanonicalChapterId(canonicalChapterId: String): List<ChapterVariant> =
            listOfNotNull(variant?.takeIf { it.canonicalChapterId == canonicalChapterId })
        override suspend fun getVariantsBySourceMappingId(sourceMappingId: String): List<ChapterVariant> =
            listOfNotNull(variant?.takeIf { it.sourceMappingId == sourceMappingId })
        override suspend fun upsert(chapter: CanonicalChapter) = Unit
        override suspend fun upsertVariant(variant: ChapterVariant) = Unit
        override suspend fun upsertBatch(chapters: List<CanonicalChapter>, variants: List<ChapterVariant>) = Unit
    }

    private class FakeCanonicalReadingRepository(
        var progress: CanonicalChapterProgress?,
    ) : CanonicalReadingRepository {
        override suspend fun getProgress(canonicalChapterId: String): CanonicalChapterProgress? =
            progress?.takeIf { it.canonicalChapterId == canonicalChapterId }

        override fun observeProgress(canonicalChapterId: String): Flow<CanonicalChapterProgress?> =
            MutableStateFlow(progress?.takeIf { it.canonicalChapterId == canonicalChapterId })

        override suspend fun getProgressByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapterProgress> =
            listOfNotNull(progress)

        override suspend fun upsertProgress(progress: CanonicalChapterProgress) {
            this.progress = progress
        }

        override suspend fun getHistory(canonicalChapterId: String): CanonicalChapterHistory? = null
        override suspend fun recordHistory(update: CanonicalChapterHistoryUpdate) = Unit
        override suspend fun recordCheckpoint(
            progress: CanonicalChapterProgress,
            history: CanonicalChapterHistoryUpdate?,
        ) {
            this.progress = progress
        }
    }
}
