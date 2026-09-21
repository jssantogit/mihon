package tachiyomi.domain.tsuzuki.reader

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.update.model.CanonicalChapterUpdateState
import tachiyomi.domain.tsuzuki.chapter.update.repository.ChapterUpdateStateRepository
import tachiyomi.domain.tsuzuki.reader.interactor.RecordCanonicalReaderProgress
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistory
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistoryUpdate
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import tachiyomi.domain.tsuzuki.reader.service.CanonicalReaderCompatibilityGateway

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
    fun `page checkpoint acknowledges new chapter update`() = runTest {
        val repository = FakeCanonicalReadingRepository()
        val updateStateRepository = FakeChapterUpdateStateRepository()
        val recorder = RecordCanonicalReaderProgress(
            repository = repository,
            chapterUpdateStateRepository = updateStateRepository,
            clock = { 700L },
        )

        recorder.recordPage(
            canonicalChapterId = "chapter-1",
            variantId = "variant-1",
            pageIndex = 0,
            completed = false,
        )

        updateStateRepository.acknowledged shouldBe "chapter-1" to 700L
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

    @Test
    fun `canonical page is persisted before mihon compatibility projection`() = runTest {
        val repository = FakeCanonicalReadingRepository()
        val compatibility = FakeCompatibilityGateway(repository)
        val recorder = RecordCanonicalReaderProgress(repository, compatibility) { 500L }

        recorder.recordPage(
            canonicalChapterId = "chapter-1",
            variantId = "variant-1",
            pageIndex = 4,
            completed = false,
            mihonChapterId = 11L,
        )

        compatibility.projectedPage shouldBe ProjectedPage(
            mihonChapterId = 11L,
            read = false,
            lastPageRead = 4L,
        )
        compatibility.canonicalProgressWasPresentForPage shouldBe true
    }

    @Test
    fun `canonical history is persisted before mihon compatibility projection`() = runTest {
        val repository = FakeCanonicalReadingRepository()
        val compatibility = FakeCompatibilityGateway(repository)
        val recorder = RecordCanonicalReaderProgress(repository, compatibility) { 900L }

        recorder.recordHistory(
            canonicalChapterId = "chapter-1",
            variantId = "variant-2",
            sessionReadDuration = 40L,
            mihonChapterId = 22L,
        )

        compatibility.projectedHistory shouldBe ProjectedHistory(
            mihonChapterId = 22L,
            readAt = 900L,
            sessionReadDuration = 40L,
        )
        compatibility.canonicalHistoryWasPresent shouldBe true
    }

    private class FakeCompatibilityGateway(
        private val repository: FakeCanonicalReadingRepository,
    ) : CanonicalReaderCompatibilityGateway {
        var projectedPage: ProjectedPage? = null
        var projectedHistory: ProjectedHistory? = null
        var canonicalProgressWasPresentForPage = false
        var canonicalHistoryWasPresent = false

        override suspend fun projectProgress(
            mihonChapterId: Long,
            read: Boolean,
            lastPageRead: Long,
        ) {
            canonicalProgressWasPresentForPage = repository.progress != null
            projectedPage = ProjectedPage(mihonChapterId, read, lastPageRead)
        }

        override suspend fun projectHistory(
            mihonChapterId: Long,
            readAt: Long,
            sessionReadDuration: Long,
        ) {
            canonicalHistoryWasPresent = repository.lastHistoryUpdate != null
            projectedHistory = ProjectedHistory(mihonChapterId, readAt, sessionReadDuration)
        }
    }

    private data class ProjectedPage(
        val mihonChapterId: Long,
        val read: Boolean,
        val lastPageRead: Long,
    )

    private data class ProjectedHistory(
        val mihonChapterId: Long,
        val readAt: Long,
        val sessionReadDuration: Long,
    )

    private class FakeChapterUpdateStateRepository : ChapterUpdateStateRepository {
        var acknowledged: Pair<String, Long>? = null

        override suspend fun getAll(): List<CanonicalChapterUpdateState> = emptyList()

        override suspend fun getByTitle(
            canonicalTitleId: String,
        ): List<CanonicalChapterUpdateState> = emptyList()

        override fun observeByTitle(
            canonicalTitleId: String,
        ): Flow<List<CanonicalChapterUpdateState>> = MutableStateFlow(emptyList())

        override suspend fun upsert(state: CanonicalChapterUpdateState) = Unit

        override suspend fun acknowledge(
            canonicalChapterId: String,
            acknowledgedAt: Long,
        ) {
            acknowledged = canonicalChapterId to acknowledgedAt
        }

        override suspend fun delete(canonicalChapterId: String) = Unit
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
