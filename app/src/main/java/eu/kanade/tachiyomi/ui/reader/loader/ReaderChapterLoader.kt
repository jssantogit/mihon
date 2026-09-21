package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter

interface ReaderChapterLoader {
    suspend fun loadChapter(chapter: ReaderChapter)
}
