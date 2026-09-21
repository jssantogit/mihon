package tachiyomi.domain.tsuzuki.updates.interactor

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.library.model.LibraryTitle
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import tachiyomi.domain.tsuzuki.updates.repository.ChapterUpdateState
import tachiyomi.domain.tsuzuki.updates.repository.ChapterUpdateStateRepository

class RecordNewCanonicalChaptersTest {

    @Test
    fun `new canonical chapters for a library title are recorded once as unacknowledged`() = runTest {
        val chapters = FakeChapterRepository(
            listOf(chapter("chapter-1", "1"), chapter("chapter-2", "2")),
        )
        val states = FakeChapterUpdateStateRepository(
            listOf(
                ChapterUpdateState(
                    canonicalChapterId = "chapter-1",
                    canonicalTitleId = "title",
                    firstSeenAt = 100L,
                    acknowledgedAt = 150L,
                ),
            ),
        )
        val record = RecordNewCanonicalChapters(
            canonicalChapterRepository = chapters,
            canonicalLibraryRepository = FakeLibraryRepository(inLibrary = true),
            chapterUpdateStateRepository = states,
            clock = { 500L },
        )

        val first = record.execute("title")
        val second = record.execute("title")

        first.map { it.canonicalChapterId } shouldContainExactly listOf("chapter-2")
        second shouldBe emptyList()
        states.getByCanonicalTitleId("title") shouldContainExactly listOf(
            ChapterUpdateState("chapter-1", "title", 100L, 150L),
            ChapterUpdateState("chapter-2", "title", 500L, null),
        )
    }

    @Test
    fun `chapters outside library never create update state`() = runTest {
        val states = FakeChapterUpdateStateRepository()
        val record = RecordNewCanonicalChapters(
            canonicalChapterRepository = FakeChapterRepository(listOf(chapter("chapter-1", "1"))),
            canonicalLibraryRepository = FakeLibraryRepository(inLibrary = false),
            chapterUpdateStateRepository = states,
            clock = { 500L },
        )

        record.execute("title") shouldBe emptyList()
        states.getByCanonicalTitleId("title") shouldBe emptyList()
    }

    private fun chapter(id: String, displayNumber: String) = CanonicalChapter(
        id = id,
        canonicalTitleId = "title",
        displayNumber = displayNumber,
        createdAt = 100L,
        updatedAt = 100L,
    )

    private class FakeChapterUpdateStateRepository(
        initial: List<ChapterUpdateState> = emptyList(),
    ) : ChapterUpdateStateRepository {
        private val states = linkedMapOf<String, ChapterUpdateState>().apply {
            initial.forEach { put(it.canonicalChapterId, it) }
        }

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<ChapterUpdateState> =
            states.values.filter { it.canonicalTitleId == canonicalTitleId }

        override suspend fun getUnacknowledgedByCanonicalTitleId(
            canonicalTitleId: String,
        ): List<ChapterUpdateState> =
            getByCanonicalTitleId(canonicalTitleId).filter { it.acknowledgedAt == null }

        override suspend fun upsert(state: ChapterUpdateState) {
            states[state.canonicalChapterId] = state
        }

        override suspend fun acknowledge(canonicalChapterId: String, acknowledgedAt: Long) {
            states[canonicalChapterId]?.let {
                states[canonicalChapterId] = it.copy(acknowledgedAt = acknowledgedAt)
            }
        }

        override suspend fun delete(canonicalChapterId: String) {
            states.remove(canonicalChapterId)
        }
    }

    private class FakeLibraryRepository(
        private val inLibrary: Boolean,
    ) : CanonicalLibraryRepository {
        override suspend fun get(canonicalTitleId: String): CanonicalLibraryEntry? =
            if (inLibrary) {
                CanonicalLibraryEntry(
                    canonicalTitleId = canonicalTitleId,
                    status = LibraryStatus.READING,
                    favorite = true,
                    addedAt = 1L,
                    updatedAt = 1L,
                )
            } else {
                null
            }

        override fun getAllAsFlow(): Flow<List<CanonicalLibraryEntry>> = MutableStateFlow(emptyList())

        override fun getAllItemsAsFlow(): Flow<List<LibraryTitle>> = MutableStateFlow(emptyList())

        override suspend fun upsert(entry: CanonicalLibraryEntry) = Unit

        override suspend fun remove(canonicalTitleId: String) = Unit
    }

    private class FakeChapterRepository(
        private val chapters: List<CanonicalChapter>,
    ) : CanonicalChapterRepository {
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapter> =
            chapters.filter { it.canonicalTitleId == canonicalTitleId }

        override fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> =
            MutableStateFlow(chapters.filter { it.canonicalTitleId == canonicalTitleId })

        override suspend fun getById(id: String): CanonicalChapter? = chapters.firstOrNull { it.id == id }

        override suspend fun getVariantBySourceIdentity(
            sourceId: Long,
            sourceChapterId: String,
        ): ChapterVariant? = null

        override suspend fun getVariantsByCanonicalChapterId(canonicalChapterId: String): List<ChapterVariant> =
            emptyList()

        override suspend fun getVariantsBySourceMappingId(sourceMappingId: String): List<ChapterVariant> =
            emptyList()

        override suspend fun upsert(chapter: CanonicalChapter) = Unit

        override suspend fun upsertVariant(variant: ChapterVariant) = Unit

        override suspend fun upsertBatch(
            chapters: List<CanonicalChapter>,
            variants: List<ChapterVariant>,
        ) = Unit
    }
}
