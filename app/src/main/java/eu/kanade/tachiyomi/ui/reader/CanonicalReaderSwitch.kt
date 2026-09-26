package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.ui.reader.loader.ReaderChapterLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import kotlinx.coroutines.CancellationException

/**
 * Stages and preflights a replacement before allowing its publication.
 * The active Reader session must only be mutated from [publish].
 */
internal suspend fun <T> prepareAndPublishCanonicalReaderChapter(
    loader: ReaderChapterLoader,
    chapter: ReaderChapter,
    preflight: suspend () -> T,
    publish: suspend (ReaderChapter, T) -> Unit,
) {
    loader.loadChapter(chapter)
    val pages = chapter.pages.orEmpty()
    require(pages.isNotEmpty()) { "The selected source returned no chapter pages" }
    chapter.requestedPage = clampCanonicalReaderPageIndex(chapter.requestedPage, pages.size)
    // Keep Reader's state collector from restoring an out-of-range database page
    // index. This is an in-memory chapter projection; canonical progress is not
    // changed until the user advances to a page.
    chapter.chapter.last_page_read = chapter.requestedPage
    val publication = preflight()
    publish(chapter, publication)
}

internal fun clampCanonicalReaderPageIndex(requestedPage: Int, pageCount: Int): Int {
    require(pageCount > 0) { "A readable chapter must contain at least one page" }
    return requestedPage.coerceIn(0, pageCount - 1)
}

internal fun isReaderChapterInActiveViewer(chapter: ReaderChapter, viewerChapters: ViewerChapters): Boolean =
    chapter === viewerChapters.currChapter ||
        chapter === viewerChapters.prevChapter ||
        chapter === viewerChapters.nextChapter

/** Suppresses the automatic initial page callback for a just-published canonical chapter. */
internal class CanonicalReaderInitialProgressGuard {
    data class Snapshot(val chapter: ReaderChapter, val pageIndex: Int)

    private var pending: Snapshot? = null

    fun snapshot(): Snapshot? = pending

    fun arm(chapter: ReaderChapter, pageIndex: Int) {
        pending = Snapshot(chapter, pageIndex)
    }

    fun restore(snapshot: Snapshot?) {
        pending = snapshot
    }

    /** Consume the first callback for this chapter, suppressing only its prepared page. */
    fun consume(chapter: ReaderChapter, pageIndex: Int): Boolean {
        val expected = pending ?: return false
        if (expected.chapter !== chapter) return false
        pending = null
        return expected.pageIndex == pageIndex
    }
}

internal suspend fun persistCanonicalReaderHistory(
    resetTimerAfterWrite: Boolean,
    resetTimer: () -> Unit,
    onFailure: (Throwable) -> Unit = {},
    writeHistory: suspend () -> Unit,
) {
    try {
        writeHistory()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        onFailure(error)
    } finally {
        if (resetTimerAfterWrite) resetTimer()
    }
}

internal inline fun <T> publishCanonicalReaderSession(
    prepare: () -> T,
    publish: (T) -> Unit,
    rollback: (T, Throwable) -> Unit,
    startReadTimer: () -> Unit,
): T {
    val staged = prepare()
    try {
        publish(staged)
    } catch (error: Throwable) {
        runCatching { rollback(staged, error) }
            .onFailure { rollbackError ->
                if (rollbackError !== error) error.addSuppressed(rollbackError)
            }
        throw error
    }
    startReadTimer()
    return staged
}
