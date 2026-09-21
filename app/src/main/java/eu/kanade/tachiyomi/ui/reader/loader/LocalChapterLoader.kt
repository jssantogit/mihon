package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.ui.reader.CanonicalLocalReaderFormat
import eu.kanade.tachiyomi.ui.reader.CanonicalReaderTargetPlan
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader

class LocalChapterLoader internal constructor(
    private val pageLoader: PageLoader,
) : ReaderChapterLoader {

    override suspend fun loadChapter(chapter: ReaderChapter) {
        if (chapter.state is ReaderChapter.State.Loaded && chapter.pageLoader != null) return

        chapter.state = ReaderChapter.State.Loading
        try {
            chapter.pageLoader = pageLoader
            val pages = pageLoader.getPages()
                .onEach { it.chapter = chapter }
            check(pages.isNotEmpty()) { "Local chapter page list is empty" }

            if (!chapter.chapter.read) {
                chapter.requestedPage = chapter.chapter.last_page_read
            }
            chapter.state = ReaderChapter.State.Loaded(pages)
        } catch (error: Throwable) {
            chapter.state = ReaderChapter.State.Error(error)
            throw error
        }
    }

    companion object {
        fun from(
            context: Context,
            plan: CanonicalReaderTargetPlan.Local,
        ): LocalChapterLoader {
            val file = UniFile.fromUri(context, Uri.parse(plan.uri))
                ?: error("Unable to open canonical local content: " + plan.uri)

            val pageLoader = when (plan.format) {
                CanonicalLocalReaderFormat.DIRECTORY -> DirectoryPageLoader(file)
                CanonicalLocalReaderFormat.EPUB -> EpubPageLoader(file.epubReader(context))
                CanonicalLocalReaderFormat.ARCHIVE -> ArchivePageLoader(file.archiveReader(context))
            }
            return LocalChapterLoader(pageLoader)
        }
    }
}
