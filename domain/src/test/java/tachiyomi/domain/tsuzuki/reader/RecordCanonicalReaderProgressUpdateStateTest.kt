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
import tachiyomi.domain.tsuzuki.reader.service.CanonicalReaderCompatibilityGateway
import tachiyomi.domain.tsuzuki.updates.repository.ChapterUpdateStateRepository

class RecordCanonicalReaderProgressUpdateStateTest {

    @Test
    fun `completing newly observed chapter acknowledges its update state`() = runTest {
        val reading = FakeCanonicalReadingRepository()
        val updates = FakeChapterUpdateStateRepository()
        val recorder = RecordCanonicalReaderProgress(
            repository = reading,
            compatibilityGateway = NoopCompatibilityGateway,
            chapterUpdateStateRepository = updates,
            clock = { 500L },
        )

        recorder.recordPage(
            canonicalChapterId = "chapter-1",
            pageIndex = 9,
            completed = true,
        )

        reading.progress?.read shouldBe true
        reading.progress?.lastVariantId shouldBe null
        updates.acknowledgedChapterId shouldBe "chapter-1"
        updates.acknowledgedAt shouldBe 500L
    }

    @Test
    fun `partial reading does not acknowledge new chapter badge`() = runTest {
        val reading = FakeCanonicalReadingRepository()
        val updates = FakeChapterUpdateStateRepository()
        val recorder = RecordCanonicalReaderProgress(
            repository = reading,
            compatibilityGateway = NoopCompatibilityGateway,
            chapterUpdateStateRepository = updates,
            clock = { 500L },
        )

        recorder.recordPage(
            canonicalChapterId = "chapter-1",
            pageIndex = 4,
            completed = false,
        )

        updates.acknowledgedChapterId shouldBe null
    }

    private class FakeChapterUpdateStateRepository : ChapterUpdateStateRepository {
        var acknowledgedChapterId: String? = null
        var acknowledgedAt: Long? = null

        override suspend fun acknowledge(canonicalChapterId: String, acknowledgedAt: Long) {
            acknowledgedChapterId = canonicalChapterId
            this.acknowledgedAt = acknowledgedAt
        }
    }

    private class FakeCanonicalReadingRepository : CanonicalReadingRepository {
        var progress: CanonicalChapterProgress? = null

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
        override suspend fun recordHistory(update: CanonicalChapterHistoryUpdate) = Unit

        override suspend fun recordCheckpoint(
            progress: CanonicalChapterProgress,
            history: CanonicalChapterHistoryUpdate?,
        ) {
            this.progress = progress
        }
    }

    private object NoopCompatibilityGateway : CanonicalReaderCompatibilityGateway {
        override suspend fun projectProgress(
            mihonChapterId: Long,
            read: Boolean,
            lastPageRead: Long,
        ) = Unit

        override suspend fun projectHistory(
            mihonChapterId: Long,
            readAt: Long,
            sessionReadDuration: Long,
        ) = Unit
    }
}
