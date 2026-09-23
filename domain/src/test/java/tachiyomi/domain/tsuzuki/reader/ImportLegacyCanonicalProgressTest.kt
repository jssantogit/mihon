package tachiyomi.domain.tsuzuki.reader

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.reader.interactor.ImportLegacyCanonicalProgress
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistory
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistoryUpdate
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import java.util.Date

class ImportLegacyCanonicalProgressTest {

    @Test
    fun `legacy progress becomes canonical without source identity leakage`() = runTest {
        val canonical = FakeCanonicalChapterRepository(
            chapters = listOf(chapter("canonical-1")),
            variants = listOf(
                variant("variant-a", chapterId = 11L, mangaId = 101L),
                variant("variant-b", chapterId = 22L, mangaId = 202L),
            ),
        )
        val chapters = FakeChapterRepository(
            chapter(11L, 101L, read = true, lastPage = 9L),
            chapter(22L, 202L, read = false, lastPage = 5L),
        )
        val history = FakeHistoryRepository(
            History(1L, 11L, Date(100L), 20L),
            History(2L, 22L, Date(300L), 30L),
        )
        val reading = FakeCanonicalReadingRepository()
        val importer = ImportLegacyCanonicalProgress(canonical, reading, chapters, history)

        importer.execute("title-1") shouldBe 1

        reading.progress shouldBe CanonicalChapterProgress(
            canonicalChapterId = "canonical-1",
            read = true,
            lastPageRead = 5L,
            lastVariantId = "variant-b",
            updatedAt = 300L,
        )
        reading.history shouldBe CanonicalChapterHistory(
            canonicalChapterId = "canonical-1",
            lastVariantId = "variant-b",
            lastReadAt = 300L,
            totalReadDuration = 50L,
        )
    }

    @Test
    fun `existing canonical progress is never overwritten by legacy state`() = runTest {
        val canonical = FakeCanonicalChapterRepository(
            chapters = listOf(chapter("canonical-1")),
            variants = listOf(variant("variant-a", chapterId = 11L, mangaId = 101L)),
        )
        val chapters = FakeChapterRepository(
            chapter(11L, 101L, read = true, lastPage = 9L),
        )
        val reading = FakeCanonicalReadingRepository().apply {
            progress = CanonicalChapterProgress(
                canonicalChapterId = "canonical-1",
                read = false,
                lastPageRead = 3L,
                lastVariantId = "variant-a",
                updatedAt = 999L,
            )
        }
        val importer = ImportLegacyCanonicalProgress(
            canonical,
            reading,
            chapters,
            FakeHistoryRepository(History(1L, 11L, Date(100L), 20L)),
        )

        importer.execute("title-1") shouldBe 0
        reading.progress?.lastPageRead shouldBe 3L
        reading.progress?.updatedAt shouldBe 999L
    }

    @Test
    fun `newer legacy activity never overwrites existing canonical progress`() = runTest {
        val canonical = FakeCanonicalChapterRepository(
            chapters = listOf(chapter("canonical-1")),
            variants = listOf(variant("variant-a", chapterId = 11L, mangaId = 101L)),
        )
        val chapters = FakeChapterRepository(
            chapter(11L, 101L, read = false, lastPage = 8L),
        )
        val reading = FakeCanonicalReadingRepository().apply {
            progress = CanonicalChapterProgress(
                canonicalChapterId = "canonical-1",
                read = false,
                lastPageRead = 3L,
                lastVariantId = "variant-a",
                updatedAt = 999L,
            )
        }
        val importer = ImportLegacyCanonicalProgress(
            canonical,
            reading,
            chapters,
            FakeHistoryRepository(History(1L, 11L, Date(1200L), 20L)),
        )

        importer.execute("title-1") shouldBe 0
        reading.progress?.lastPageRead shouldBe 3L
        reading.progress?.updatedAt shouldBe 999L
    }

    private fun chapter(id: String) = CanonicalChapter(
        id = id,
        canonicalTitleId = "title-1",
        displayNumber = "1",
        type = CanonicalChapterType.REGULAR,
        baseNumber = 1,
        confidence = 1.0,
    )

    private fun variant(id: String, chapterId: Long, mangaId: Long) = ChapterVariant(
        id = id,
        canonicalChapterId = "canonical-1",
        sourceMappingId = "mapping-$mangaId",
        sourceId = mangaId,
        mihonMangaId = mangaId,
        mihonChapterId = chapterId,
        sourceChapterId = "/chapter/$chapterId",
        sourceChapterUrl = "/chapter/$chapterId",
        language = "en",
        rawName = "Chapter 1",
    )

    private fun chapter(
        id: Long,
        mangaId: Long,
        read: Boolean,
        lastPage: Long,
    ) = Chapter.create().copy(
        id = id,
        mangaId = mangaId,
        url = "/chapter/$id",
        name = "Chapter 1",
        read = read,
        lastPageRead = lastPage,
    )

    private class FakeCanonicalChapterRepository(
        private val chapters: List<CanonicalChapter>,
        private val variants: List<ChapterVariant>,
    ) : CanonicalChapterRepository {
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapter> =
            chapters.filter { it.canonicalTitleId == canonicalTitleId }

        override fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> =
            MutableStateFlow(chapters.filter { it.canonicalTitleId == canonicalTitleId })

        override suspend fun getById(id: String): CanonicalChapter? = chapters.firstOrNull { it.id == id }

        override suspend fun getVariantBySourceIdentity(
            sourceId: Long,
            sourceChapterId: String,
        ): ChapterVariant? = variants.firstOrNull {
            it.sourceId == sourceId && it.sourceChapterId == sourceChapterId
        }

        override suspend fun getVariantsByCanonicalChapterId(canonicalChapterId: String): List<ChapterVariant> =
            variants.filter { it.canonicalChapterId == canonicalChapterId }

        override suspend fun getVariantsBySourceMappingId(sourceMappingId: String): List<ChapterVariant> =
            variants.filter { it.sourceMappingId == sourceMappingId }

        override suspend fun upsert(chapter: CanonicalChapter) = Unit
        override suspend fun upsertVariant(variant: ChapterVariant) = Unit
        override suspend fun upsertBatch(
            chapters: List<CanonicalChapter>,
            variants: List<ChapterVariant>,
        ) = Unit
    }

    private class FakeChapterRepository(
        vararg chapters: Chapter,
    ) : ChapterRepository {
        private val rows = chapters.associateBy { it.id }.toMutableMap()

        override suspend fun getChapterById(id: Long): Chapter? = rows[id]

        override suspend fun getChapterByUrlAndMangaId(url: String, mangaId: Long): Chapter? =
            rows.values.firstOrNull { it.url == url && it.mangaId == mangaId }

        override suspend fun addAll(chapters: List<Chapter>): List<Chapter> = chapters
        override suspend fun update(chapterUpdate: ChapterUpdate) = Unit
        override suspend fun updateAll(chapterUpdates: List<ChapterUpdate>) = Unit
        override suspend fun removeChaptersWithIds(chapterIds: List<Long>) = Unit
        override suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean): List<Chapter> =
            rows.values.filter { it.mangaId == mangaId }

        override suspend fun getScanlatorsByMangaId(mangaId: Long): List<String> = emptyList()
        override fun getScanlatorsByMangaIdAsFlow(mangaId: Long): Flow<List<String>> = MutableStateFlow(emptyList())
        override suspend fun getBookmarkedChaptersByMangaId(mangaId: Long): List<Chapter> = emptyList()
        override suspend fun getChapterByMangaIdAsFlow(
            mangaId: Long,
            applyScanlatorFilter: Boolean,
        ): Flow<List<Chapter>> = MutableStateFlow(rows.values.filter { it.mangaId == mangaId })
    }

    private class FakeHistoryRepository(
        vararg histories: History,
    ) : HistoryRepository {
        private val rows = histories.toList()

        override suspend fun getHistoryByMangaId(mangaId: Long): List<History> = rows
        override fun getHistory(query: String): Flow<List<HistoryWithRelations>> = MutableStateFlow(emptyList())
        override suspend fun getLastHistory(): HistoryWithRelations? = null
        override suspend fun getTotalReadDuration(): Long = rows.sumOf { it.readDuration }
        override suspend fun resetHistory(historyId: Long) = Unit
        override suspend fun resetHistoryByMangaId(mangaId: Long) = Unit
        override suspend fun deleteAllHistory(): Boolean = true
        override suspend fun upsertHistory(historyUpdate: HistoryUpdate) = Unit
    }

    private class FakeCanonicalReadingRepository : CanonicalReadingRepository {
        var progress: CanonicalChapterProgress? = null
        var history: CanonicalChapterHistory? = null

        override suspend fun getProgress(canonicalChapterId: String): CanonicalChapterProgress? =
            progress?.takeIf { it.canonicalChapterId == canonicalChapterId }

        override fun observeProgress(canonicalChapterId: String): Flow<CanonicalChapterProgress?> =
            MutableStateFlow(progress)

        override suspend fun getProgressByCanonicalTitleId(
            canonicalTitleId: String,
        ): List<CanonicalChapterProgress> = listOfNotNull(progress)

        override suspend fun upsertProgress(progress: CanonicalChapterProgress) {
            this.progress = progress
        }

        override suspend fun getHistory(canonicalChapterId: String): CanonicalChapterHistory? =
            history?.takeIf { it.canonicalChapterId == canonicalChapterId }

        override suspend fun recordHistory(update: CanonicalChapterHistoryUpdate) {
            history = CanonicalChapterHistory(
                canonicalChapterId = update.canonicalChapterId,
                lastVariantId = update.variantId,
                lastReadAt = update.readAt,
                totalReadDuration = (history?.totalReadDuration ?: 0L) + update.sessionReadDuration,
            )
        }

        override suspend fun recordCheckpoint(
            progress: CanonicalChapterProgress,
            history: CanonicalChapterHistoryUpdate?,
        ) {
            this.progress = progress
            history?.let { recordHistory(it) }
        }
    }
}
