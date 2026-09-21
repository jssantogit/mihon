package tachiyomi.domain.tsuzuki.sync.adapter

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistory
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistoryUpdate
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import tachiyomi.domain.tsuzuki.sync.service.CanonicalTitleSyncSource
import tachiyomi.domain.tsuzuki.sync.service.ChapterSyncEvidenceRepository
import tachiyomi.domain.tsuzuki.sync.service.SyncClock
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision
import tachiyomi.domain.tsuzuki.sync.service.SyncRevisionSource

class CanonicalReadingProgressSyncAdapterTest {

    @Test
    fun `progress maps across different local chapter and variant ids by portable keys`() = runTest {
        val chapterA = chapter(id = "chapter-local-a")
        val chapterB = chapter(id = "chapter-local-b")
        val variantA = variant(id = "variant-local-a", chapterId = chapterA.id)
        val variantB = variant(id = "variant-local-b", chapterId = chapterB.id)

        val readingA = FakeReadingRepository().apply {
            upsertProgress(
                CanonicalChapterProgress(
                    canonicalChapterId = chapterA.id,
                    read = false,
                    lastPageRead = 5,
                    lastVariantId = variantA.id,
                    updatedAt = 100,
                ),
            )
        }
        val readingB = FakeReadingRepository()

        val exported = adapter(
            chapters = FakeChapterRepository(listOf(chapterA), listOf(variantA)),
            reading = readingA,
        ).exportDocument()

        adapter(
            chapters = FakeChapterRepository(listOf(chapterB), listOf(variantB)),
            reading = readingB,
        ).applyDocument(exported)

        readingB.getProgress(chapterB.id) shouldBe CanonicalChapterProgress(
            canonicalChapterId = chapterB.id,
            read = false,
            lastPageRead = 5,
            lastVariantId = variantB.id,
            updatedAt = 100,
        )
        readingB.getProgress(chapterA.id) shouldBe null
    }

    @Test
    fun `unknown chapter without stable evidence stays local and marks adapter unportable`() = runTest {
        val unknown = CanonicalChapter(
            id = "unknown-local",
            canonicalTitleId = "title-1",
            displayNumber = "?",
            type = CanonicalChapterType.UNKNOWN,
        )
        val reading = FakeReadingRepository().apply {
            upsertProgress(
                CanonicalChapterProgress(
                    canonicalChapterId = unknown.id,
                    lastPageRead = 2,
                    updatedAt = 10,
                ),
            )
        }
        val adapter = adapter(
            chapters = FakeChapterRepository(listOf(unknown), emptyList()),
            reading = reading,
        )

        val exported = adapter.exportDocument()

        exported.records shouldBe emptyMap()
        adapter.hasUnportableLocalState() shouldBe true
    }

    private fun adapter(
        chapters: CanonicalChapterRepository,
        reading: CanonicalReadingRepository,
    ) = CanonicalReadingProgressSyncAdapter(
        titleSource = CanonicalTitleSyncSource { listOf(title()) },
        chapterRepository = chapters,
        readingRepository = reading,
        evidenceRepository = ChapterSyncEvidenceRepository { null },
        revisionSource = FakeRevisionSource(),
        clock = object : SyncClock {
            override fun nowEpochMillis(): Long = 100L
        },
    )

    private fun title() = CanonicalTitle(
        id = "title-1",
        displayTitle = "Example",
        identityState = CanonicalIdentityState.RESOLVED,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun chapter(id: String) = CanonicalChapter(
        id = id,
        canonicalTitleId = "title-1",
        displayNumber = "12",
        type = CanonicalChapterType.REGULAR,
        baseNumber = 12,
    )

    private fun variant(
        id: String,
        chapterId: String,
    ) = ChapterVariant(
        id = id,
        canonicalChapterId = chapterId,
        sourceMappingId = "mapping-local",
        sourceId = 10,
        sourceChapterId = "chapter-12",
        rawName = "Chapter 12",
    )

    private class FakeRevisionSource : SyncRevisionSource {
        override val deviceId = "device-test"
        private var sequence = 0L

        override fun nextRevision() = SyncRevision(
            deviceId = deviceId,
            sequence = ++sequence,
        )
    }

    private class FakeChapterRepository(
        private val chapters: List<CanonicalChapter>,
        variants: List<ChapterVariant>,
    ) : CanonicalChapterRepository {
        private val variants = variants.toMutableList()

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String) =
            chapters.filter { it.canonicalTitleId == canonicalTitleId }

        override fun observeByCanonicalTitleId(canonicalTitleId: String) =
            flowOf(chapters.filter { it.canonicalTitleId == canonicalTitleId })

        override suspend fun getById(id: String) = chapters.find { it.id == id }

        override suspend fun getVariantBySourceIdentity(
            sourceId: Long,
            sourceChapterId: String,
        ) = variants.find {
            it.sourceId == sourceId && it.sourceChapterId == sourceChapterId
        }

        override suspend fun getVariantsByCanonicalChapterId(canonicalChapterId: String) =
            variants.filter { it.canonicalChapterId == canonicalChapterId }

        override suspend fun getVariantsBySourceMappingId(sourceMappingId: String) =
            variants.filter { it.sourceMappingId == sourceMappingId }

        override suspend fun upsert(chapter: CanonicalChapter) = error("not used")

        override suspend fun upsertVariant(variant: ChapterVariant) = error("not used")

        override suspend fun upsertBatch(
            chapters: List<CanonicalChapter>,
            variants: List<ChapterVariant>,
        ) = error("not used")
    }

    private class FakeReadingRepository : CanonicalReadingRepository {
        private val progress = mutableMapOf<String, CanonicalChapterProgress>()

        override suspend fun getProgress(canonicalChapterId: String) =
            progress[canonicalChapterId]

        override fun observeProgress(canonicalChapterId: String): Flow<CanonicalChapterProgress?> =
            flowOf(progress[canonicalChapterId])

        override suspend fun getProgressByCanonicalTitleId(canonicalTitleId: String) =
            progress.values.toList()

        override fun observeProgressByCanonicalTitleId(
            canonicalTitleId: String,
        ): Flow<List<CanonicalChapterProgress>> = flowOf(progress.values.toList())

        override suspend fun upsertProgress(progress: CanonicalChapterProgress) {
            this.progress[progress.canonicalChapterId] = progress
        }

        override suspend fun getHistory(canonicalChapterId: String): CanonicalChapterHistory? =
            null

        override suspend fun recordHistory(update: CanonicalChapterHistoryUpdate) = Unit

        override suspend fun recordCheckpoint(
            progress: CanonicalChapterProgress,
            history: CanonicalChapterHistoryUpdate?,
        ) {
            upsertProgress(progress)
        }
    }
}
