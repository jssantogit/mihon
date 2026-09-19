package tachiyomi.domain.tsuzuki.home

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.service.CatalogProvider
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.home.interactor.GetHomeCatalogFeed
import tachiyomi.domain.tsuzuki.home.interactor.ObserveHomeContinueReading
import tachiyomi.domain.tsuzuki.library.interactor.ObserveCanonicalLibrary
import tachiyomi.domain.tsuzuki.library.model.CanonicalLibraryItem
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistory
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistoryUpdate
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository

class BasicHomeTest {

    @Test
    fun `continue reading is derived from canonical local progress only`() = runTest {
        val libraryRepository = FakeCanonicalLibraryRepository(
            listOf(libraryItem("title-1", "Title One"), libraryItem("title-2", "Title Two")),
        )
        val chapterRepository = FakeCanonicalChapterRepository(
            mapOf(
                "title-1" to listOf(chapter("chapter-1", "title-1", 1), chapter("chapter-2", "title-1", 2)),
                "title-2" to listOf(chapter("chapter-3", "title-2", 3)),
            ),
        )
        val readingRepository = FakeCanonicalReadingRepository(
            mapOf(
                "title-1" to listOf(
                    progress("chapter-1", read = false, page = 2L, updatedAt = 100L),
                    progress("chapter-2", read = false, page = 5L, updatedAt = 300L),
                ),
                "title-2" to listOf(
                    progress("chapter-3", read = true, page = 10L, updatedAt = 400L),
                ),
            ),
        )

        val items = ObserveHomeContinueReading(
            observeCanonicalLibrary = ObserveCanonicalLibrary(libraryRepository),
            canonicalChapterRepository = chapterRepository,
            canonicalReadingRepository = readingRepository,
        ).subscribe().first()

        items.map { it.canonicalChapterId } shouldContainExactly listOf("chapter-2")
        items.single().title shouldBe "Title One"
        items.single().lastPageRead shouldBe 5L
    }

    @Test
    fun `remote home sections degrade independently`() = runTest {
        val provider = FakeCatalogProvider()
        val feed = GetHomeCatalogFeed(provider).execute(limit = 5)

        feed.recentlyUpdated.isSuccess shouldBe true
        feed.trending.isFailure shouldBe true
        feed.popular.isSuccess shouldBe true
        feed.isDegraded shouldBe true
        feed.isCompleteFailure shouldBe false
        feed.recentlyUpdated.getOrThrow().items.single().title shouldBe "Recent"
        feed.popular.getOrThrow().items.single().title shouldBe "Popular"
    }

    private fun libraryItem(id: String, title: String) = CanonicalLibraryItem(
        title = CanonicalTitle(
            id = id,
            displayTitle = title,
            identityState = CanonicalIdentityState.RESOLVED,
            createdAt = 100L,
            updatedAt = 100L,
        ),
        entry = CanonicalLibraryEntry(
            canonicalTitleId = id,
            status = LibraryStatus.READING,
            favorite = true,
            addedAt = 100L,
            updatedAt = 100L,
        ),
    )

    private fun chapter(id: String, titleId: String, number: Int) = CanonicalChapter(
        id = id,
        canonicalTitleId = titleId,
        displayNumber = number.toString(),
        type = CanonicalChapterType.REGULAR,
        baseNumber = number,
        confidence = 1.0,
    )

    private fun progress(
        chapterId: String,
        read: Boolean,
        page: Long,
        updatedAt: Long,
    ) = CanonicalChapterProgress(
        canonicalChapterId = chapterId,
        read = read,
        lastPageRead = page,
        updatedAt = updatedAt,
    )

    private class FakeCanonicalLibraryRepository(
        items: List<CanonicalLibraryItem>,
    ) : CanonicalLibraryRepository {
        private val flow = MutableStateFlow(items)
        override suspend fun get(canonicalTitleId: String): CanonicalLibraryEntry? =
            flow.value.firstOrNull { it.title.id == canonicalTitleId }?.entry
        override fun getAllAsFlow(): Flow<List<CanonicalLibraryEntry>> =
            MutableStateFlow(flow.value.map { it.entry })
        override fun getAllItemsAsFlow(): Flow<List<CanonicalLibraryItem>> = flow
        override suspend fun upsert(entry: CanonicalLibraryEntry) = Unit
        override suspend fun remove(canonicalTitleId: String) = Unit
    }

    private class FakeCanonicalChapterRepository(
        private val byTitle: Map<String, List<CanonicalChapter>>,
    ) : CanonicalChapterRepository {
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapter> =
            byTitle[canonicalTitleId].orEmpty()
        override fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> =
            MutableStateFlow(byTitle[canonicalTitleId].orEmpty())
        override suspend fun getById(id: String): CanonicalChapter? =
            byTitle.values.flatten().firstOrNull { it.id == id }
        override suspend fun getVariantBySourceIdentity(sourceId: Long, sourceChapterId: String): ChapterVariant? = null
        override suspend fun getVariantsByCanonicalChapterId(
            canonicalChapterId: String,
        ): List<ChapterVariant> = emptyList()
        override suspend fun getVariantsBySourceMappingId(sourceMappingId: String): List<ChapterVariant> = emptyList()
        override suspend fun upsert(chapter: CanonicalChapter) = Unit
        override suspend fun upsertVariant(variant: ChapterVariant) = Unit
        override suspend fun upsertBatch(chapters: List<CanonicalChapter>, variants: List<ChapterVariant>) = Unit
    }

    private class FakeCanonicalReadingRepository(
        private val byTitle: Map<String, List<CanonicalChapterProgress>>,
    ) : CanonicalReadingRepository {
        override suspend fun getProgress(canonicalChapterId: String): CanonicalChapterProgress? =
            byTitle.values.flatten().firstOrNull { it.canonicalChapterId == canonicalChapterId }
        override fun observeProgress(canonicalChapterId: String): Flow<CanonicalChapterProgress?> =
            MutableStateFlow(null)
        override suspend fun getProgressByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapterProgress> =
            byTitle[canonicalTitleId].orEmpty()
        override suspend fun upsertProgress(progress: CanonicalChapterProgress) = Unit
        override suspend fun getHistory(canonicalChapterId: String): CanonicalChapterHistory? = null
        override suspend fun recordHistory(update: CanonicalChapterHistoryUpdate) = Unit
        override suspend fun recordCheckpoint(
            progress: CanonicalChapterProgress,
            history: CanonicalChapterHistoryUpdate?,
        ) = Unit
    }

    private class FakeCatalogProvider : CatalogProvider {
        override val providerId: String = "fake"
        override val displayName: String = "Fake"

        override suspend fun search(query: CatalogQuery): Result<CatalogPage> =
            Result.success(CatalogPage(listOf(item("recent", "Recent")), false, 1))

        override suspend fun getTrending(offset: Int, limit: Int): Result<CatalogPage> =
            Result.failure(IllegalStateException("trending unavailable"))

        override suspend fun getPopular(offset: Int, limit: Int): Result<CatalogPage> =
            Result.success(CatalogPage(listOf(item("popular", "Popular")), false, 1))

        override suspend fun getDetails(providerId: String): Result<CatalogItem> =
            Result.success(item(providerId, providerId))

        private fun item(id: String, title: String) = CatalogItem(
            provider = providerId,
            providerId = id,
            title = title,
            titles = emptyMap(),
            synopsis = null,
            coverUrl = null,
            bannerUrl = null,
            status = CatalogItemStatus.UNKNOWN,
            format = CatalogItemFormat.UNKNOWN,
            score = null,
            genres = emptyList(),
            tags = emptyList(),
            startDate = null,
            endDate = null,
            chapterCount = null,
            volumeCount = null,
        )
    }
}
