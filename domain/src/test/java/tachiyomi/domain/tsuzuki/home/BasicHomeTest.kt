package tachiyomi.domain.tsuzuki.home

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.chapter.update.model.CanonicalChapterUpdateState
import tachiyomi.domain.tsuzuki.chapter.update.repository.ChapterUpdateStateRepository
import tachiyomi.domain.tsuzuki.home.interactor.ObserveHomeContinueReading
import tachiyomi.domain.tsuzuki.home.model.ContinueReadingVisibility
import tachiyomi.domain.tsuzuki.home.model.HomeContinueReadingSeed
import tachiyomi.domain.tsuzuki.home.repository.ContinueReadingVisibilityRepository
import tachiyomi.domain.tsuzuki.home.repository.HomeContinueReadingSource
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
        val fixture = fixture(
            libraryItems = listOf(
                libraryItem("title-1", "Title One"),
                libraryItem("title-2", "Title Two"),
            ),
            chapters = mapOf(
                "title-1" to listOf(
                    chapter("chapter-1", "title-1", 1),
                    chapter("chapter-2", "title-1", 2),
                ),
                "title-2" to listOf(chapter("chapter-3", "title-2", 3)),
            ),
            progress = mapOf(
                "title-1" to listOf(
                    progress("chapter-1", read = false, page = 2L, updatedAt = 100L),
                    progress("chapter-2", read = false, page = 5L, updatedAt = 300L),
                ),
                "title-2" to listOf(
                    progress("chapter-3", read = true, page = 10L, updatedAt = 400L),
                ),
            ),
        )

        val items = fixture.observer.subscribe().first()

        items.map { it.canonicalChapterId } shouldContainExactly listOf("chapter-2")
        items.single().title shouldBe "Title One"
        items.single().lastPageRead shouldBe 5L
    }

    @Test
    fun `continue reading reacts to reader progress without a library mutation`() = runTest {
        val fixture = fixture(
            libraryItems = listOf(libraryItem("title-1", "Title One")),
            chapters = mapOf(
                "title-1" to listOf(chapter("chapter-1", "title-1", 1)),
            ),
            progress = mapOf(
                "title-1" to listOf(
                    progress("chapter-1", read = false, page = 0L, updatedAt = 0L),
                ),
            ),
        )

        val updated = async {
            fixture.observer.subscribe().first { it.isNotEmpty() }
        }
        runCurrent()

        fixture.readingRepository.upsertProgress(
            progress("chapter-1", read = false, page = 4L, updatedAt = 500L),
        )
        runCurrent()

        updated.await().single().let { item ->
            item.canonicalChapterId shouldBe "chapter-1"
            item.lastPageRead shouldBe 4L
            item.updatedAt shouldBe 500L
        }
    }

    @Test
    fun `remove from continue reading hides current progress but newer checkpoint restores eligibility`() = runTest {
        val fixture = fixture(
            libraryItems = listOf(libraryItem("title-1", "Title One")),
            chapters = mapOf(
                "title-1" to listOf(chapter("chapter-1", "title-1", 1)),
            ),
            progress = mapOf(
                "title-1" to listOf(
                    progress("chapter-1", read = false, page = 3L, updatedAt = 500L),
                ),
            ),
        )

        fixture.observer.subscribe().first().single().canonicalTitleId shouldBe "title-1"

        val hidden = async {
            fixture.observer.subscribe().first { it.isEmpty() }
        }
        runCurrent()
        fixture.visibilityRepository.hide(
            canonicalTitleId = "title-1",
            hiddenAt = 500L,
        )
        runCurrent()
        hidden.await() shouldBe emptyList()

        val restored = async {
            fixture.observer.subscribe().first { it.isNotEmpty() }
        }
        runCurrent()
        fixture.readingRepository.upsertProgress(
            progress("chapter-1", read = false, page = 4L, updatedAt = 501L),
        )
        runCurrent()

        restored.await().single().updatedAt shouldBe 501L
    }

    @Test
    fun `new chapter badge decrements as chapters are read or acknowledged`() = runTest {
        val fixture = fixture(
            libraryItems = listOf(libraryItem("title-1", "Title One")),
            chapters = mapOf(
                "title-1" to listOf(
                    chapter("chapter-1", "title-1", 1),
                    chapter("chapter-2", "title-1", 2),
                    chapter("chapter-3", "title-1", 3),
                    chapter("chapter-4", "title-1", 4),
                ),
            ),
            progress = mapOf(
                "title-1" to listOf(
                    progress("chapter-1", read = false, page = 4L, updatedAt = 500L),
                    progress("chapter-2", read = false, page = 0L, updatedAt = 0L),
                    progress("chapter-3", read = false, page = 0L, updatedAt = 0L),
                    progress("chapter-4", read = false, page = 0L, updatedAt = 0L),
                ),
            ),
            updates = mapOf(
                "title-1" to listOf(
                    update("chapter-2", "title-1"),
                    update("chapter-3", "title-1"),
                    update("chapter-4", "title-1"),
                ),
            ),
        )

        fixture.observer.subscribe().first().single().newChapterCount shouldBe 3

        val afterRead = async {
            fixture.observer.subscribe().first {
                it.singleOrNull()?.newChapterCount == 2
            }
        }
        runCurrent()
        fixture.readingRepository.upsertProgress(
            progress("chapter-2", read = true, page = 10L, updatedAt = 600L),
        )
        runCurrent()
        afterRead.await().single().newChapterCount shouldBe 2

        val afterAck = async {
            fixture.observer.subscribe().first {
                it.singleOrNull()?.newChapterCount == 1
            }
        }
        runCurrent()
        fixture.chapterUpdateRepository.acknowledge("chapter-3", 700L)
        runCurrent()
        afterAck.await().single().newChapterCount shouldBe 1

        val cleared = async {
            fixture.observer.subscribe().first {
                it.singleOrNull()?.newChapterCount == 0
            }
        }
        runCurrent()
        fixture.readingRepository.upsertProgress(
            progress("chapter-4", read = true, page = 10L, updatedAt = 800L),
        )
        runCurrent()
        cleared.await().single().newChapterCount shouldBe 0
    }

    private fun fixture(
        libraryItems: List<CanonicalLibraryItem>,
        chapters: Map<String, List<CanonicalChapter>>,
        progress: Map<String, List<CanonicalChapterProgress>>,
        updates: Map<String, List<CanonicalChapterUpdateState>> = emptyMap(),
    ): Fixture {
        val chapterRepository = FakeCanonicalChapterRepository(chapters)
        val readingRepository = FakeCanonicalReadingRepository(progress)
        val visibilityRepository = FakeContinueReadingVisibilityRepository()
        val updateRepository = FakeChapterUpdateStateRepository(updates)
        val source = FakeHomeContinueReadingSource(
            titles = libraryItems.associate { it.title.id to it.title.displayTitle },
            chapters = chapters,
            readingRepository = readingRepository,
        )

        return Fixture(
            observer = ObserveHomeContinueReading(
                source = source,
                visibilityRepository = visibilityRepository,
                chapterUpdateStateRepository = updateRepository,
            ),
            readingRepository = readingRepository,
            visibilityRepository = visibilityRepository,
            chapterUpdateRepository = updateRepository,
        )
    }

    private data class Fixture(
        val observer: ObserveHomeContinueReading,
        val readingRepository: FakeCanonicalReadingRepository,
        val visibilityRepository: FakeContinueReadingVisibilityRepository,
        val chapterUpdateRepository: FakeChapterUpdateStateRepository,
    )

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

    private fun chapter(
        id: String,
        titleId: String,
        number: Int,
    ) = CanonicalChapter(
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

    private fun update(
        chapterId: String,
        titleId: String,
    ) = CanonicalChapterUpdateState(
        canonicalChapterId = chapterId,
        canonicalTitleId = titleId,
        firstSeenAt = 100L,
    )

    private class FakeHomeContinueReadingSource(
        private val titles: Map<String, String>,
        private val chapters: Map<String, List<CanonicalChapter>>,
        private val readingRepository: CanonicalReadingRepository,
    ) : HomeContinueReadingSource {
        override fun observe(): Flow<List<HomeContinueReadingSeed>> {
            if (titles.isEmpty()) return MutableStateFlow(emptyList())
            return combine(
                titles.keys.map { titleId ->
                    readingRepository.observeProgressByCanonicalTitleId(titleId)
                },
            ) { progressByTitle ->
                titles.keys.zip(progressByTitle.asList()).flatMap { (titleId, progress) ->
                    val chaptersById = chapters[titleId].orEmpty().associateBy { it.id }
                    progress.mapNotNull { item ->
                        val chapter = chaptersById[item.canonicalChapterId] ?: return@mapNotNull null
                        HomeContinueReadingSeed(
                            canonicalTitleId = titleId,
                            title = titles.getValue(titleId),
                            canonicalChapterId = chapter.id,
                            chapterDisplayNumber = chapter.displayNumber,
                            lastPageRead = item.lastPageRead,
                            read = item.read,
                            updatedAt = item.updatedAt,
                            lastVariantId = item.lastVariantId,
                        )
                    }
                }
            }
        }
    }

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
        override suspend fun getByCanonicalTitleId(
            canonicalTitleId: String,
        ): List<CanonicalChapter> = byTitle[canonicalTitleId].orEmpty()

        override fun observeByCanonicalTitleId(
            canonicalTitleId: String,
        ): Flow<List<CanonicalChapter>> =
            MutableStateFlow(byTitle[canonicalTitleId].orEmpty())

        override suspend fun getById(id: String): CanonicalChapter? =
            byTitle.values.flatten().firstOrNull { it.id == id }

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

    private class FakeCanonicalReadingRepository(
        byTitle: Map<String, List<CanonicalChapterProgress>>,
    ) : CanonicalReadingRepository {
        private val chapterIdsByTitle = byTitle.mapValues { (_, items) ->
            items.map { it.canonicalChapterId }
        }
        private val progressByChapter = byTitle.values
            .flatten()
            .associate { progress ->
                progress.canonicalChapterId to MutableStateFlow<CanonicalChapterProgress?>(progress)
            }
            .toMutableMap()

        override suspend fun getProgress(
            canonicalChapterId: String,
        ): CanonicalChapterProgress? =
            progressByChapter[canonicalChapterId]?.value

        override fun observeProgress(
            canonicalChapterId: String,
        ): Flow<CanonicalChapterProgress?> =
            progressByChapter.getOrPut(canonicalChapterId) { MutableStateFlow(null) }

        override suspend fun getProgressByCanonicalTitleId(
            canonicalTitleId: String,
        ): List<CanonicalChapterProgress> =
            chapterIdsByTitle[canonicalTitleId]
                .orEmpty()
                .mapNotNull { progressByChapter[it]?.value }

        override fun observeProgressByCanonicalTitleId(
            canonicalTitleId: String,
        ): Flow<List<CanonicalChapterProgress>> {
            val chapterIds = chapterIdsByTitle[canonicalTitleId].orEmpty()
            if (chapterIds.isEmpty()) return MutableStateFlow(emptyList())
            return combine(chapterIds.map(::observeProgress)) { values ->
                values.filterNotNull()
            }
        }

        override suspend fun upsertProgress(progress: CanonicalChapterProgress) {
            progressByChapter
                .getOrPut(progress.canonicalChapterId) { MutableStateFlow(null) }
                .value = progress
        }

        override suspend fun getHistory(
            canonicalChapterId: String,
        ): CanonicalChapterHistory? = null

        override suspend fun recordHistory(
            update: CanonicalChapterHistoryUpdate,
        ) = Unit

        override suspend fun recordCheckpoint(
            progress: CanonicalChapterProgress,
            history: CanonicalChapterHistoryUpdate?,
        ) {
            upsertProgress(progress)
        }
    }

    private class FakeContinueReadingVisibilityRepository :
        ContinueReadingVisibilityRepository {

        private val flow = MutableStateFlow<List<ContinueReadingVisibility>>(emptyList())

        override suspend fun get(
            canonicalTitleId: String,
        ): ContinueReadingVisibility? =
            flow.value.firstOrNull { it.canonicalTitleId == canonicalTitleId }

        override suspend fun getAll(): List<ContinueReadingVisibility> = flow.value

        override fun observeAll(): Flow<List<ContinueReadingVisibility>> = flow

        override suspend fun hide(
            canonicalTitleId: String,
            hiddenAt: Long,
        ) {
            flow.value = flow.value
                .filterNot { it.canonicalTitleId == canonicalTitleId } +
                ContinueReadingVisibility(canonicalTitleId, hiddenAt)
        }

        override suspend fun clear(canonicalTitleId: String) {
            flow.value = flow.value.filterNot { it.canonicalTitleId == canonicalTitleId }
        }
    }

    private class FakeChapterUpdateStateRepository(
        initial: Map<String, List<CanonicalChapterUpdateState>>,
    ) : ChapterUpdateStateRepository {

        private val byTitle = initial
            .mapValues { (_, value) -> MutableStateFlow(value) }
            .toMutableMap()

        override suspend fun getAll(): List<CanonicalChapterUpdateState> =
            byTitle.values.flatMap { it.value }

        override suspend fun getByTitle(
            canonicalTitleId: String,
        ): List<CanonicalChapterUpdateState> =
            byTitle[canonicalTitleId]?.value.orEmpty()

        override fun observeByTitle(
            canonicalTitleId: String,
        ): Flow<List<CanonicalChapterUpdateState>> =
            byTitle.getOrPut(canonicalTitleId) { MutableStateFlow(emptyList()) }

        override suspend fun upsert(state: CanonicalChapterUpdateState) {
            val flow = byTitle.getOrPut(state.canonicalTitleId) {
                MutableStateFlow(emptyList())
            }
            flow.value = flow.value
                .filterNot { it.canonicalChapterId == state.canonicalChapterId } + state
        }

        override suspend fun acknowledge(
            canonicalChapterId: String,
            acknowledgedAt: Long,
        ) {
            val entry = byTitle.entries.firstOrNull { (_, flow) ->
                flow.value.any { it.canonicalChapterId == canonicalChapterId }
            } ?: return
            entry.value.value = entry.value.value.map { state ->
                if (state.canonicalChapterId == canonicalChapterId) {
                    state.copy(acknowledgedAt = acknowledgedAt)
                } else {
                    state
                }
            }
        }

        override suspend fun delete(canonicalChapterId: String) {
            byTitle.values.forEach { flow ->
                flow.value = flow.value.filterNot {
                    it.canonicalChapterId == canonicalChapterId
                }
            }
        }
    }
}
