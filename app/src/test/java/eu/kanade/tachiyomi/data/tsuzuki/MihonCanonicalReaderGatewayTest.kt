package eu.kanade.tachiyomi.data.tsuzuki

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress

class MihonCanonicalReaderGatewayTest {

    @Test
    fun `materialize projects canonical progress onto selected operational chapter`() = runTest {
        val chapters = FakeChapterRepository().apply {
            rows[30L] = Chapter.create().copy(
                id = 30L,
                mangaId = 20L,
                url = "/chapter/1",
                name = "Chapter 1",
                read = false,
                lastPageRead = 1L,
            )
        }
        val gateway = MihonCanonicalReaderGateway(chapters)

        val target = gateway.materialize(
            canonicalChapterId = "chapter-1",
            delivery = ContentDelivery.Mihon(
                sourceId = 7L,
                mangaId = 20L,
                chapterId = 30L,
            ),
            progress = CanonicalChapterProgress(
                canonicalChapterId = "chapter-1",
                read = true,
                lastPageRead = 8L,
                lastVariantId = null,
                updatedAt = 100L,
            ),
        ).getOrThrow()

        target.canonicalChapterId shouldBe "chapter-1"
        target.mihonMangaId shouldBe 20L
        target.mihonChapterId shouldBe 30L
        target.sourceId shouldBe 7L
        chapters.rows.getValue(30L).read shouldBe true
        chapters.rows.getValue(30L).lastPageRead shouldBe 8L
    }

    @Test
    fun `materialize fails closed when operational coordinates do not match`() = runTest {
        val chapters = FakeChapterRepository().apply {
            rows[30L] = Chapter.create().copy(
                id = 30L,
                mangaId = 99L,
                url = "/chapter/1",
                name = "Chapter 1",
            )
        }
        val gateway = MihonCanonicalReaderGateway(chapters)

        gateway.materialize(
            canonicalChapterId = "chapter-1",
            delivery = ContentDelivery.Mihon(
                sourceId = 7L,
                mangaId = 20L,
                chapterId = 30L,
            ),
            progress = null,
        ).isFailure shouldBe true

        gateway.materialize(
            canonicalChapterId = "chapter-1",
            delivery = ContentDelivery.Mihon(
                sourceId = 7L,
                mangaId = 20L,
                chapterId = 404L,
            ),
            progress = null,
        ).isFailure shouldBe true
    }

    @Test
    fun `materialize rejects progress from another canonical chapter`() = runTest {
        val chapters = FakeChapterRepository().apply {
            rows[30L] = Chapter.create().copy(
                id = 30L,
                mangaId = 20L,
                url = "/chapter/1",
                name = "Chapter 1",
            )
        }
        val gateway = MihonCanonicalReaderGateway(chapters)

        gateway.materialize(
            canonicalChapterId = "chapter-1",
            delivery = ContentDelivery.Mihon(7L, 20L, 30L),
            progress = CanonicalChapterProgress(canonicalChapterId = "other"),
        ).isFailure shouldBe true
    }

    @Test
    fun `cancellation from operational projection propagates`() = runTest {
        val chapters = FakeChapterRepository().apply {
            rows[30L] = Chapter.create().copy(
                id = 30L,
                mangaId = 20L,
                url = "/chapter/1",
                name = "Chapter 1",
            )
            updateError = CancellationException("cancelled")
        }
        val gateway = MihonCanonicalReaderGateway(chapters)

        shouldThrow<CancellationException> {
            gateway.materialize(
                canonicalChapterId = "chapter-1",
                delivery = ContentDelivery.Mihon(7L, 20L, 30L),
                progress = CanonicalChapterProgress(
                    canonicalChapterId = "chapter-1",
                    read = true,
                    lastPageRead = 2L,
                ),
            )
        }
    }

    private class FakeChapterRepository : ChapterRepository {
        val rows = linkedMapOf<Long, Chapter>()
        var updateError: Throwable? = null

        override suspend fun addAll(chapters: List<Chapter>): List<Chapter> = chapters

        override suspend fun update(chapterUpdate: ChapterUpdate) {
            updateError?.let { throw it }
            val current = rows[chapterUpdate.id] ?: return
            rows[chapterUpdate.id] = current.copy(
                read = chapterUpdate.read ?: current.read,
                lastPageRead = chapterUpdate.lastPageRead ?: current.lastPageRead,
            )
        }

        override suspend fun updateAll(chapterUpdates: List<ChapterUpdate>) {
            chapterUpdates.forEach { update(it) }
        }

        override suspend fun removeChaptersWithIds(chapterIds: List<Long>) {
            chapterIds.forEach(rows::remove)
        }

        override suspend fun getChapterByMangaId(
            mangaId: Long,
            applyScanlatorFilter: Boolean,
        ): List<Chapter> = rows.values.filter { it.mangaId == mangaId }

        override suspend fun getScanlatorsByMangaId(mangaId: Long): List<String> = emptyList()
        override fun getScanlatorsByMangaIdAsFlow(mangaId: Long): Flow<List<String>> = emptyFlow()
        override suspend fun getBookmarkedChaptersByMangaId(mangaId: Long): List<Chapter> = emptyList()
        override suspend fun getChapterById(id: Long): Chapter? = rows[id]

        override suspend fun getChapterByMangaIdAsFlow(
            mangaId: Long,
            applyScanlatorFilter: Boolean,
        ): Flow<List<Chapter>> = MutableStateFlow(rows.values.filter { it.mangaId == mangaId })

        override suspend fun getChapterByUrlAndMangaId(url: String, mangaId: Long): Chapter? =
            rows.values.firstOrNull { it.url == url && it.mangaId == mangaId }
    }
}
