package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.ui.reader.loader.ReaderChapterLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class CanonicalReaderSwitchTest {

    @Test
    fun `replacement timer starts after replacement session publication`() {
        var timerStart: Long? = 10L
        var activeSession = "previous"

        publishCanonicalReaderSession(
            prepare = { Unit },
            publish = { activeSession = "replacement" },
            rollback = { _, _ -> activeSession = "previous" },
            startReadTimer = { timerStart = 20L },
        )

        activeSession shouldBe "replacement"
        timerStart shouldBe 20L
    }

    @Test
    fun `failed preparation preserves previous session history and timer`() {
        var timerStart: Long? = 10L
        var activeSession = "previous"
        var historyWrites = 0

        val error = runCatching {
            publishCanonicalReaderSession(
                prepare = { error("queued download preparation failed") },
                publish = {
                    activeSession = "replacement"
                    historyWrites++
                },
                rollback = { _, _ -> activeSession = "previous" },
                startReadTimer = { timerStart = 20L },
            )
        }.exceptionOrNull()

        error.shouldBeInstanceOf<IllegalStateException>()
        activeSession shouldBe "previous"
        historyWrites shouldBe 0
        timerStart shouldBe 10L
    }

    @Test
    fun `partial state publication is rolled back and preserves previous timer`() {
        var timerStart: Long? = 10L
        var activeSession = "previous"

        val error = runCatching {
            publishCanonicalReaderSession(
                prepare = { Unit },
                publish = {
                    activeSession = "replacement partially published"
                    error("state update failed")
                },
                rollback = { _, _ -> activeSession = "previous" },
                startReadTimer = { timerStart = 20L },
            )
        }.exceptionOrNull()

        error.shouldBeInstanceOf<IllegalStateException>()
        activeSession shouldBe "previous"
        timerStart shouldBe 10L
    }

    @Test
    fun `initial progress suppression is one shot and ignores retired chapter callbacks`() {
        val retired = chapter("same-chapter-id")
        val replacement = chapter("same-chapter-id")
        val guard = CanonicalReaderInitialProgressGuard()
        guard.arm(replacement, pageIndex = 0)

        guard.consume(retired, pageIndex = 0) shouldBe false
        guard.consume(replacement, pageIndex = 1) shouldBe false
        guard.consume(replacement, pageIndex = 0) shouldBe false

        guard.arm(replacement, pageIndex = 0)
        guard.consume(replacement, pageIndex = 0) shouldBe true
        guard.consume(replacement, pageIndex = 0) shouldBe false
    }

    @Test
    fun `retired viewer chapter cannot be mistaken for replacement chapter with same id`() {
        val retired = chapter("same-chapter-id")
        val replacement = chapter("same-chapter-id")

        isReaderChapterInActiveViewer(retired, ViewerChapters(replacement, null, null)) shouldBe false
        isReaderChapterInActiveViewer(replacement, ViewerChapters(replacement, null, null)) shouldBe true
    }

    @Test
    fun `delayed switch history write does not clear the new session timer`() = runTest {
        var timerStart: Long? = null
        val writeStarted = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()

        val write = async {
            persistCanonicalReaderHistory(
                resetTimerAfterWrite = false,
                resetTimer = { timerStart = null },
            ) {
                writeStarted.complete(Unit)
                finishWrite.await()
            }
        }
        writeStarted.await()
        timerStart = 20L
        finishWrite.complete(Unit)
        write.await()

        timerStart shouldBe 20L
    }

    @Test
    fun `post publication history failure is isolated and keeps the new timer`() = runTest {
        var timerStart: Long? = 20L
        var failure: Throwable? = null

        persistCanonicalReaderHistory(
            resetTimerAfterWrite = false,
            resetTimer = { timerStart = null },
            onFailure = { failure = it },
        ) {
            error("history write failed")
        }

        failure?.message shouldBe "history write failed"
        timerStart shouldBe 20L
    }

    @Test
    fun `history cancellation propagates without clearing replacement timer`() = runTest {
        var timerStart: Long? = 20L

        val error = runCatching {
            persistCanonicalReaderHistory(
                resetTimerAfterWrite = false,
                resetTimer = { timerStart = null },
            ) {
                throw CancellationException("cancelled")
            }
        }.exceptionOrNull()

        error.shouldBeInstanceOf<CancellationException>()
        timerStart shouldBe 20L
    }

    @Test
    fun `staged page index is clamped to the replacement source page count`() = runTest {
        val replacement = chapter("replacement").apply {
            requestedPage = 12
            chapter.last_page_read = 12
        }
        val loader = object : ReaderChapterLoader {
            override suspend fun loadChapter(chapter: ReaderChapter) {
                chapter.state = ReaderChapter.State.Loaded(
                    listOf(ReaderPage(0), ReaderPage(1), ReaderPage(2)),
                )
            }
        }

        prepareAndPublishCanonicalReaderChapter(
            loader = loader,
            chapter = replacement,
            preflight = { Unit },
            publish = { stagedChapter, _ ->
                stagedChapter.requestedPage shouldBe 2
                stagedChapter.chapter.last_page_read shouldBe 2
            },
        )

        replacement.requestedPage shouldBe 2
        replacement.chapter.last_page_read shouldBe 2
    }

    @Test
    fun `ordinary history write still resets its timer after persistence`() = runTest {
        var timerStart: Long? = 20L

        persistCanonicalReaderHistory(
            resetTimerAfterWrite = true,
            resetTimer = { timerStart = null },
            writeHistory = {},
        )

        timerStart shouldBe null
    }

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
        prepareAndPublishCanonicalReaderChapter(
            loader = loader,
            chapter = replacement,
            preflight = { Unit },
            publish = { chapter, _ ->
                committedPreviousHistory = true
                chapter shouldBe replacement
            },
        )

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
            prepareAndPublishCanonicalReaderChapter(
                loader = loader,
                chapter = replacement,
                preflight = { Unit },
                publish = { _, _ -> committedPreviousHistory = true },
            )
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
            prepareAndPublishCanonicalReaderChapter(
                loader = loader,
                chapter = replacement,
                preflight = { Unit },
                publish = { _, _ -> committedPreviousHistory = true },
            )
        }.exceptionOrNull()
        error.shouldBeInstanceOf<IllegalStateException>()
        committedPreviousHistory shouldBe false
        previous.pages?.size shouldBe 1
    }

    @Test
    fun `publication preflight failure leaves the reader session and history untouched`() = runTest {
        val previous = chapter("previous")
        previous.state = ReaderChapter.State.Loaded(listOf(ReaderPage(0)))
        val replacement = chapter("replacement")
        val loader = object : ReaderChapterLoader {
            override suspend fun loadChapter(chapter: ReaderChapter) {
                chapter.state = ReaderChapter.State.Loaded(listOf(ReaderPage(0), ReaderPage(1)))
            }
        }
        var activeChapter = previous
        var activeCanonicalChapterId = "canonical-previous"
        var canonicalProgressPage = 4
        var historyEntries = 1

        val error = runCatching {
            prepareAndPublishCanonicalReaderChapter(
                loader = loader,
                chapter = replacement,
                preflight = { throw IllegalStateException("Adjacent chapter lookup failed") },
                publish = { preparedChapter, _ ->
                    historyEntries += 1
                    activeChapter = preparedChapter
                    activeCanonicalChapterId = "canonical-replacement"
                    canonicalProgressPage = 0
                },
            )
        }.exceptionOrNull()

        error.shouldBeInstanceOf<IllegalStateException>()
        activeChapter shouldBe previous
        activeCanonicalChapterId shouldBe "canonical-previous"
        canonicalProgressPage shouldBe 4
        historyEntries shouldBe 1
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
