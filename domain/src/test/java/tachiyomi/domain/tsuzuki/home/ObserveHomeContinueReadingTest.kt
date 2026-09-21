package tachiyomi.domain.tsuzuki.home

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.chapter.update.model.CanonicalChapterUpdateState
import tachiyomi.domain.tsuzuki.chapter.update.repository.ChapterUpdateStateRepository
import tachiyomi.domain.tsuzuki.home.interactor.ObserveHomeContinueReading
import tachiyomi.domain.tsuzuki.home.model.ContinueReadingVisibility
import tachiyomi.domain.tsuzuki.home.repository.ContinueReadingVisibilityRepository
import tachiyomi.domain.tsuzuki.library.interactor.ObserveCanonicalLibrary
import tachiyomi.domain.tsuzuki.library.model.LibraryTitle
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistory
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistoryUpdate
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository

class ObserveHomeContinueReadingTest {

    @Test
    fun `continue reading appears only after actual reader checkpoint`() = runTest {
        val fixture = Fixture()

        fixture.observe.subscribe().first() shouldBe emptyList()

        fixture.progress.value = listOf(
            CanonicalChapterProgress(
                canonicalChapterId = "chapter-1",
                lastPageRead = 0,
                lastVariantId = "variant-1",
                updatedAt = 10,
            ),
        )

        fixture.observe.subscribe().first().single().canonicalChapterId shouldBe "chapter-1"
    }

    @Test
    fun `remove from continue reading hides until a newer checkpoint exists`() = runTest {
        val fixture = Fixture()
        fixture.progress.value = listOf(
            CanonicalChapterProgress(
                canonicalChapterId = "chapter-1",
                lastPageRead = 3,
                lastVariantId = "variant-1",
                updatedAt = 50,
            ),
        )
        fixture.visibility.value = listOf(
            ContinueReadingVisibility(
                canonicalTitleId = "title-1",
                hiddenAt = 50,
            ),
        )

        fixture.observe.subscribe().first() shouldBe emptyList()

        fixture.progress.value = listOf(
            CanonicalChapterProgress(
                canonicalChapterId = "chapter-1",
                lastPageRead = 4,
                lastVariantId = "variant-1",
                updatedAt = 51,
            ),
        )

        fixture.observe.subscribe().first().single().lastPageRead shouldBe 4
    }

    @Test
    fun `new chapter badge decreases as chapter updates are acknowledged`() = runTest {
        val fixture = Fixture()
        fixture.progress.value = listOf(
            CanonicalChapterProgress(
                canonicalChapterId = "chapter-1",
                lastPageRead = 1,
                lastVariantId = "variant-1",
                updatedAt = 10,
            ),
        )
        fixture.updates.value = listOf(
            updateState("chapter-1"),
            updateState("chapter-2"),
            updateState("chapter-3"),
        )

        fixture.observe.subscribe().first().single().newChapterCount shouldBe 3

        fixture.updates.value = fixture.updates.value.map { state ->
            if (state.canonicalChapterId == "chapter-2") {
                state.copy(acknowledgedAt = 20)
            } else {
                state
            }
        }

        fixture.observe.subscribe().first().single().newChapterCount shouldBe 2
    }

    private class Fixture {
        val progress = MutableStateFlow<List<CanonicalChapterProgress>>(emptyList())
        val visibility = MutableStateFlow<List<ContinueReadingVisibility>>(emptyList())
        val updates = MutableStateFlow<List<CanonicalChapterUpdateState>>(emptyList())

        private val libraryRepository = FakeLibraryRepository()
        private val chapterRepository = FakeChapterRepository()
        private val readingRepository = FakeReadingRepository(progress)
        private val visibilityRepository = FakeVisibilityRepository(visibility)
        private val updateRepository = FakeUpdateRepository(updates)

        val observe = ObserveHomeContinueReading(
            observeCanonicalLibrary = ObserveCanonicalLibrary(libraryRepository),
            canonicalChapterRepository = chapterRepository,
            canonicalReadingRepository = readingRepository,
            visibilityRepository = visibilityRepository,
            chapterUpdateStateRepository = updateRepository,
        )
    }

    private class FakeLibraryRepository : CanonicalLibraryRepository {
        private val title = CanonicalTitle(
            id = "title-1",
            displayTitle = "Dandadan",
            identityState = CanonicalIdentityState.RESOLVED,
            createdAt = 1,
            updatedAt = 1,
        )
        private val entry = CanonicalLibraryEntry(
            canonicalTitleId = title.id,
            status = LibraryStatus.READING,
            favorite = true,
            addedAt = 1,
            updatedAt = 1,
        )
        private val items = MutableStateFlow(
            listOf(
                LibraryTitle(
                    title = title,
                    entry = entry,
                ),
            ),
        )

        override suspend fun get(canonicalTitleId: String) =
            entry.takeIf { it.canonicalTitleId == canonicalTitleId }

        override fun getAllAsFlow(): Flow<List<CanonicalLibraryEntry>> =
            MutableStateFlow(listOf(entry))

        override fun getAllItemsAsFlow(): Flow<List<LibraryTitle>> = items

        override suspend fun upsert(entry: CanonicalLibraryEntry) = Unit

        override suspend fun remove(canonicalTitleId: String) = Unit
    }

    private class FakeChapterRepository : CanonicalChapterRepository {
        private val chapters = MutableStateFlow(
            listOf(
                CanonicalChapter(
                    id = "chapter-1",
                    canonicalTitleId = "title-1",
                    displayNumber = "1",
                    createdAt = 1,
                    updatedAt = 1,
                ),
            ),
        )

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String) =
            chapters.value.filter { it.canonicalTitleId == canonicalTitleId }

        override fun observeByCanonicalTitleId(
            canonicalTitleId: String,
        ): Flow<List<CanonicalChapter>> = chapters

        override suspend fun getById(id: String) =
            chapters.value.firstOrNull { it.id == id }

        override suspend fun getVariantBySourceIdentity(
            sourceId: Long,
            sourceChapterId: String,
        ): ChapterVariant? = null

        override suspend fun getVariantsByCanonicalChapterId(
            canonicalChapterId: String,
        ): List<ChapterVariant> = emptyList()

        override suspend fun getVariantsBySourceMappingId(
            sourceMappingId: String,
        ): List<ChapterVariant> = emptyList()

        override suspend fun upsert(chapter: CanonicalChapter) = Unit

        override suspend fun upsertVariant(variant: ChapterVariant) = Unit

        override suspend fun upsertBatch(
            chapters: List<CanonicalChapter>,
            variants: List<ChapterVariant>,
        ) = Unit
    }

    private class FakeReadingRepository(
        private val progress: MutableStateFlow<List<CanonicalChapterProgress>>,
    ) : CanonicalReadingRepository {
        override suspend fun getProgress(canonicalChapterId: String) =
            progress.value.firstOrNull { it.canonicalChapterId == canonicalChapterId }

        override fun observeProgress(
            canonicalChapterId: String,
        ): Flow<CanonicalChapterProgress?> =
            MutableStateFlow(
                progress.value.firstOrNull { it.canonicalChapterId == canonicalChapterId },
            )

        override suspend fun getProgressByCanonicalTitleId(
            canonicalTitleId: String,
        ) = progress.value

        override fun observeProgressByCanonicalTitleId(
            canonicalTitleId: String,
        ): Flow<List<CanonicalChapterProgress>> = progress

        override suspend fun upsertProgress(progress: CanonicalChapterProgress) = Unit

        override suspend fun getHistory(canonicalChapterId: String): CanonicalChapterHistory? = null

        override suspend fun recordHistory(update: CanonicalChapterHistoryUpdate) = Unit

        override suspend fun recordCheckpoint(
            progress: CanonicalChapterProgress,
            history: CanonicalChapterHistoryUpdate?,
        ) = Unit
    }

    private class FakeVisibilityRepository(
        private val visibility: MutableStateFlow<List<ContinueReadingVisibility>>,
    ) : ContinueReadingVisibilityRepository {
        override suspend fun get(canonicalTitleId: String) =
            visibility.value.firstOrNull { it.canonicalTitleId == canonicalTitleId }

        override suspend fun getAll() = visibility.value

        override fun observeAll(): Flow<List<ContinueReadingVisibility>> = visibility

        override suspend fun hide(canonicalTitleId: String, hiddenAt: Long) = Unit

        override suspend fun clear(canonicalTitleId: String) = Unit
    }

    private class FakeUpdateRepository(
        private val updates: MutableStateFlow<List<CanonicalChapterUpdateState>>,
    ) : ChapterUpdateStateRepository {
        override suspend fun getAll() = updates.value

        override suspend fun getByTitle(canonicalTitleId: String) =
            updates.value.filter { it.canonicalTitleId == canonicalTitleId }

        override fun observeByTitle(
            canonicalTitleId: String,
        ): Flow<List<CanonicalChapterUpdateState>> = updates

        override suspend fun upsert(state: CanonicalChapterUpdateState) = Unit

        override suspend fun acknowledge(
            canonicalChapterId: String,
            acknowledgedAt: Long,
        ) = Unit

        override suspend fun delete(canonicalChapterId: String) = Unit
    }

    private fun updateState(chapterId: String) = CanonicalChapterUpdateState(
        canonicalChapterId = chapterId,
        canonicalTitleId = "title-1",
        firstSeenAt = 1,
    )
}
