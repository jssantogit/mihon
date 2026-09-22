package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.ui.reader.loader.ReaderChapterLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter

/**
 * Prepares a replacement chapter without mutating the active Reader session.
 * Callers publish the replacement only after this function returns successfully.
 */
internal suspend fun stageCanonicalReaderChapter(
    loader: ReaderChapterLoader,
    chapter: ReaderChapter,
    onStaged: suspend () -> Unit = {},
): ReaderChapter {
    loader.loadChapter(chapter)
    require(!chapter.pages.isNullOrEmpty()) { "The selected source returned no chapter pages" }
    onStaged()
    return chapter
}
