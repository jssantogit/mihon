package tachiyomi.domain.tsuzuki.home

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.update.model.CanonicalChapterUpdateState
import tachiyomi.domain.tsuzuki.chapter.update.repository.ChapterUpdateStateRepository
import tachiyomi.domain.tsuzuki.home.interactor.ObserveHomeContinueReading
import tachiyomi.domain.tsuzuki.home.model.ContinueReadingVisibility
import tachiyomi.domain.tsuzuki.home.model.HomeContinueReadingSeed
import tachiyomi.domain.tsuzuki.home.repository.ContinueReadingVisibilityRepository
import tachiyomi.domain.tsuzuki.home.repository.HomeContinueReadingSource

class ObserveHomeContinueReadingTest {

    @Test
    fun `progress appears without Library membership`() = runTest {
        val fixture = Fixture()
        fixture.progress.value = listOf(seed(lastPageRead = 3, updatedAt = 10))

        val item = fixture.observe.subscribe().first().single()

        item.canonicalTitleId shouldBe "title-1"
        item.canonicalChapterId shouldBe "chapter-1"
        item.lastPageRead shouldBe 3
    }

    @Test
    fun `remove from continue reading hides until a newer checkpoint exists`() = runTest {
        val fixture = Fixture()
        fixture.progress.value = listOf(seed(lastPageRead = 3, updatedAt = 50))
        fixture.visibility.value = listOf(
            ContinueReadingVisibility(
                canonicalTitleId = "title-1",
                hiddenAt = 50,
            ),
        )

        fixture.observe.subscribe().first() shouldBe emptyList()

        fixture.progress.value = listOf(seed(lastPageRead = 4, updatedAt = 51))

        fixture.observe.subscribe().first().single().lastPageRead shouldBe 4
    }

    @Test
    fun `new chapter badge excludes chapters already read`() = runTest {
        val fixture = Fixture()
        fixture.progress.value = listOf(
            seed(lastPageRead = 1, updatedAt = 10),
            seed(
                chapterId = "chapter-2",
                displayNumber = "2",
                read = true,
                lastPageRead = 20,
                updatedAt = 9,
            ),
        )
        fixture.updates.value = listOf(
            updateState("chapter-1"),
            updateState("chapter-2"),
            updateState("chapter-3"),
        )

        fixture.observe.subscribe().first().single().newChapterCount shouldBe 2
    }

    private class Fixture {
        val progress = MutableStateFlow<List<HomeContinueReadingSeed>>(emptyList())
        val visibility = MutableStateFlow<List<ContinueReadingVisibility>>(emptyList())
        val updates = MutableStateFlow<List<CanonicalChapterUpdateState>>(emptyList())

        val observe = ObserveHomeContinueReading(
            source = FakeSource(progress),
            visibilityRepository = FakeVisibilityRepository(visibility),
            chapterUpdateStateRepository = FakeUpdateRepository(updates),
        )
    }

    private class FakeSource(
        private val progress: MutableStateFlow<List<HomeContinueReadingSeed>>,
    ) : HomeContinueReadingSource {
        override fun observe(): Flow<List<HomeContinueReadingSeed>> = progress
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
        ): Flow<List<CanonicalChapterUpdateState>> =
            MutableStateFlow(updates.value.filter { it.canonicalTitleId == canonicalTitleId })

        override suspend fun upsert(state: CanonicalChapterUpdateState) = Unit
        override suspend fun acknowledge(canonicalChapterId: String, acknowledgedAt: Long) = Unit
        override suspend fun delete(canonicalChapterId: String) = Unit
    }

    private fun seed(
        chapterId: String = "chapter-1",
        displayNumber: String = "1",
        read: Boolean = false,
        lastPageRead: Long = 0,
        updatedAt: Long = 1,
    ) = HomeContinueReadingSeed(
        canonicalTitleId = "title-1",
        title = "Dandadan",
        canonicalChapterId = chapterId,
        chapterDisplayNumber = displayNumber,
        lastPageRead = lastPageRead,
        read = read,
        updatedAt = updatedAt,
        lastVariantId = null,
    )

    private fun updateState(chapterId: String) = CanonicalChapterUpdateState(
        canonicalChapterId = chapterId,
        canonicalTitleId = "title-1",
        firstSeenAt = 1,
    )
}
