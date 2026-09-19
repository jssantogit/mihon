package tachiyomi.domain.tsuzuki.reader

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.reader.interactor.RecordCanonicalReaderProgress
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistory
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistoryUpdate
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository

class RecordCanonicalReaderProgressTest {

    @Test
    fun `page checkpoints preserve completed state and update canonical page`() = runTest {
        val repository = FakeCanonicalReadingRepository()
        val recorder = RecordCanonicalReaderProgress(repository) { 500L }

        recorder.recordPage("chapter-1", "variant-1", pageIndex = 4, completed = false)
        repository.progress shouldBe CanonicalChapterProgress(
            canonicalChapterId = "chapter-1",
            read = false,
            lastPageRead = 4L,
            lastVariantId = "variant-1",
            updatedAt = 500L,
        )

        repository.progress = repository.progress!!.copy(read = true)
        recorder.recordPage("chapter-1", "variant-1", pageIndex = 2, completed = false)

        repository.progress shouldBe CanonicalChapterProgress(
            canonicalChapterId = "chapter-1",
            read = true,
            lastPageRead = 2L,
            lastVariantId = "variant-1",
            updatedAt = 500L,
        )
    }

    @Test
    fun `completion marks canonical chapter read and history keeps variant context`() = runTest {
        val repository = FakeCanonicalReadingRepository()
        val recorder = RecordCanonicalReaderProgress(repository) { 900L }

        recorder.recordPage("chapter-1", "variant-2", pageIndex = 9, completed = true)
        recorder.recordHistory("chapter-1", "variant-2", sessionReadDuration = 40L)

        repository.progress?.read shouldBe true
        repository.lastHistoryUpdate shouldBe CanonicalChapterHistoryUpdate(
            canonicalChapterId = "chapter-1",
            variantId = "variant-2",
            readAt = 900L,
            sessionReadDuration = 40L,
        )
    }

    private class FakeCanonicalReadingRepository : CanonicalReadingRepository {
        var progress: CanonicalChapterProgress? = null
        var lastHistoryUpdate: CanonicalChapterHistoryUpdate? = null

        override suspend fun getProgress(canonicalChapterId: String): CanonicalChapterProgress? = progress

        override fun observeProgress(canonicalChapterId: String): Flow<CanonicalChapterProgress?> =
            MutableStateFlow(progress)

        override suspend fun getProgressByCanonicalTitleId(
            canonicalTitleId: String,
        ): List<CanonicalChapterProgress> = listOfNotNull(progress)

        override suspend fun upsertProgress(progress: CanonicalChapterProgress) {
            this.progress = progress
        }

        override suspend fun getHistory(canonicalChapterId: String): CanonicalChapterHistory? = null

        override suspend fun recordHistory(update: CanonicalChapterHistoryUpdate) {
            lastHistoryUpdate = update
        }

        override suspend fun recordCheckpoint(
            progress: CanonicalChapterProgress,
            history: CanonicalChapterHistoryUpdate?,
        ) {
            this.progress = progress
            lastHistoryUpdate = history
        }
    }
}
