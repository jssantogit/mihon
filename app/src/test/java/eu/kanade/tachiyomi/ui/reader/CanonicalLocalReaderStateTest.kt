package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress

class CanonicalLocalReaderStateTest {

    @Test
    fun `local resume uses canonical progress even when last variant came from another addon`() {
        val progress = CanonicalChapterProgress(
            canonicalChapterId = "chapter-1",
            read = false,
            lastPageRead = 8L,
            lastVariantId = "old-variant",
            updatedAt = 100L,
        )

        resolveCanonicalLocalRequestedPage(
            resetPage = false,
            savedPageIndex = -1,
            progress = progress,
        ) shouldBe 8
    }

    @Test
    fun `reset discards saved process page but keeps persisted canonical progress`() {
        val progress = CanonicalChapterProgress(
            canonicalChapterId = "chapter-1",
            lastPageRead = 8L,
            updatedAt = 100L,
        )

        resolveCanonicalLocalRequestedPage(
            resetPage = true,
            savedPageIndex = 4,
            progress = progress,
        ) shouldBe 8
    }

    @Test
    fun `saved process page wins over persisted progress when not resetting`() {
        val progress = CanonicalChapterProgress(
            canonicalChapterId = "chapter-1",
            lastPageRead = 8L,
            updatedAt = 100L,
        )

        resolveCanonicalLocalRequestedPage(
            resetPage = false,
            savedPageIndex = 4,
            progress = progress,
        ) shouldBe 4
    }

    @Test
    fun `local viewer chapters initialize reader without legacy manga`() {
        val chapter = ReaderChapter(
            ChapterImpl().apply {
                id = Long.MIN_VALUE
                url = "content://downloads/chapter-1.cbz"
                name = "Chapter 1"
            },
        )
        val state = ReaderViewModel.State(
            manga = null,
            viewerChapters = ViewerChapters(chapter, null, null),
        )

        state.needsViewerInitialization() shouldBe true
        ReaderViewModel.State().needsViewerInitialization() shouldBe false
    }
}
