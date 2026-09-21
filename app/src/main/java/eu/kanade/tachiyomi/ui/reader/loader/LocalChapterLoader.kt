package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent

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
            target: PreparedChapterContent,
        ): LocalChapterLoader {
            val uri = when (target) {
                is PreparedChapterContent.LocalArchive -> target.uri
                is PreparedChapterContent.LocalDirectory -> target.uri
                is PreparedChapterContent.CanonicalDownload -> target.uri
                is PreparedChapterContent.MihonOperational ->
                    error("Mihon operational content is not local content")
            }
            val file = UniFile.fromUri(context, Uri.parse(uri))
                ?: error("Unable to open canonical local content: $uri")
            val formatHint = (target as? PreparedChapterContent.CanonicalDownload)?.format

            val pageLoader = when {
                target is PreparedChapterContent.LocalDirectory -> DirectoryPageLoader(file)
                formatHint.equals("DIRECTORY", ignoreCase = true) -> DirectoryPageLoader(file)
                formatHint.equals("EPUB", ignoreCase = true) -> EpubPageLoader(file.epubReader(context))
                file.name.orEmpty().endsWith(".epub", ignoreCase = true) ->
                    EpubPageLoader(file.epubReader(context))
                else -> ArchivePageLoader(file.archiveReader(context))
            }
            return LocalChapterLoader(pageLoader)
        }
    }
}
