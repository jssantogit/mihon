package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class LocalChapterLoaderTest {

    @Test
    fun localLoaderUsesProviderNeutralPageLoaderAndCanonicalPagePosition() = runTest {
        val pages = listOf(
            ReaderPage(0).apply { status = Page.State.Ready },
            ReaderPage(1).apply { status = Page.State.Ready },
        )
        val pageLoader = FakePageLoader(pages)
        val loader = LocalChapterLoader(pageLoader)
        val chapter = ReaderChapter(
            ChapterImpl().apply {
                id = Long.MIN_VALUE
                url = "content://downloads/chapter-1.cbz"
                name = "Chapter 1"
                last_page_read = 1
            },
        )

        loader.loadChapter(chapter)

        chapter.pages shouldBe pages
        chapter.requestedPage shouldBe 1
        pages.all { it.chapter === chapter } shouldBe true
        chapter.pageLoader shouldBe pageLoader
    }

    private class FakePageLoader(
        private val pages: List<ReaderPage>,
    ) : PageLoader() {
        override var isLocal: Boolean = true

        override suspend fun getPages(): List<ReaderPage> = pages
    }
}