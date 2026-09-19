package tachiyomi.domain.tsuzuki.reader

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.reader.interactor.GetAdjacentCanonicalChapter
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterDirection

class GetAdjacentCanonicalChapterTest {

    @Test
    fun `navigation follows canonical repository order and stops at boundaries`() = runTest {
        val chapters = listOf(
            chapter("prologue", CanonicalChapterType.PROLOGUE, null),
            chapter("chapter-1", CanonicalChapterType.REGULAR, 1),
            chapter("chapter-2", CanonicalChapterType.REGULAR, 2),
            chapter("extra-1", CanonicalChapterType.EXTRA, 1),
        )
        val interactor = GetAdjacentCanonicalChapter(FakeCanonicalChapterRepository(chapters))

        interactor.execute("chapter-1", CanonicalChapterDirection.PREVIOUS)?.id shouldBe "prologue"
        interactor.execute("chapter-1", CanonicalChapterDirection.NEXT)?.id shouldBe "chapter-2"
        interactor.execute("prologue", CanonicalChapterDirection.PREVIOUS) shouldBe null
        interactor.execute("extra-1", CanonicalChapterDirection.NEXT) shouldBe null
    }

    private fun chapter(
        id: String,
        type: CanonicalChapterType,
        number: Int?,
    ) = CanonicalChapter(
        id = id,
        canonicalTitleId = "title-1",
        displayNumber = number?.toString() ?: id,
        type = type,
        baseNumber = number,
        confidence = 1.0,
        createdAt = 100L,
        updatedAt = 100L,
    )

    private class FakeCanonicalChapterRepository(
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
