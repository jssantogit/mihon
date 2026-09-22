package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.ui.reader.loader.ReaderChapterLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class CanonicalReaderSwitchTest {

    @Test
    fun `staging a valid replacement keeps the previous loaded chapter intact`() = runTest {
        val previous = chapter("previous")
        previous.state = ReaderChapter.State.Loaded(listOf(ReaderPage(0)))
        val replacement = chapter("replacement")
        val loader = object : ReaderChapterLoader {
            override suspend fun loadChapter(chapter: ReaderChapter) {
                chapter.state = ReaderChapter.State.Loaded(listOf(ReaderPage(0), ReaderPage(1)))
            }
        }

        var committedPreviousHistory = false
        stageCanonicalReaderChapter(loader, replacement) {
            committedPreviousHistory = true
        } shouldBe replacement

        committedPreviousHistory shouldBe true

        previous.pages?.size shouldBe 1
        replacement.pages?.size shouldBe 2
    }

    @Test
    fun `staging rejects empty pages without changing the previous chapter`() = runTest {
        val previous = chapter("previous")
        previous.state = ReaderChapter.State.Loaded(listOf(ReaderPage(0)))
        val replacement = chapter("empty")
        val loader = object : ReaderChapterLoader {
            override suspend fun loadChapter(chapter: ReaderChapter) {
                chapter.state = ReaderChapter.State.Loaded(emptyList())
            }
        }

        var committedPreviousHistory = false
        val error = runCatching {
            stageCanonicalReaderChapter(loader, replacement) {
                committedPreviousHistory = true
            }
        }.exceptionOrNull()
        error.shouldBeInstanceOf<IllegalArgumentException>()
        committedPreviousHistory shouldBe false
        previous.pages?.size shouldBe 1
    }

    @Test
    fun `staging failure leaves the active chapter readable`() = runTest {
        val previous = chapter("previous")
        previous.state = ReaderChapter.State.Loaded(listOf(ReaderPage(0)))
        val replacement = chapter("failed")
        val loader = object : ReaderChapterLoader {
            override suspend fun loadChapter(chapter: ReaderChapter): Unit =
                throw IllegalStateException("Source unavailable")
        }

        var committedPreviousHistory = false
        val error = runCatching {
            stageCanonicalReaderChapter(loader, replacement) {
                committedPreviousHistory = true
            }
        }.exceptionOrNull()
        error.shouldBeInstanceOf<IllegalStateException>()
        committedPreviousHistory shouldBe false
        previous.pages?.size shouldBe 1
    }

    private fun chapter(url: String): ReaderChapter = ReaderChapter(
        ChapterImpl().apply {
            id = 1L
            name = url
            this.url = url
        },
    )
}
