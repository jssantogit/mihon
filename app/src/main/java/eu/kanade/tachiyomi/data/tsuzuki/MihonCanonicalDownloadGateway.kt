package eu.kanade.tachiyomi.data.tsuzuki

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.CancellationException
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.download.service.CanonicalDownloadGateway

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MihonCanonicalDownloadGateway internal constructor(
    private val mangaTitleProvider: suspend (Long) -> String?,
    private val sourceProvider: suspend (Long) -> Source?,
    private val downloadLookup: suspend (ChapterVariant, String, Source) -> Boolean,
) : CanonicalDownloadGateway {

    @Inject
    constructor(
        mangaRepository: MangaRepository,
        sourceManager: SourceManager,
        downloadManager: DownloadManager,
    ) : this(
        mangaTitleProvider = { mangaId ->
            try {
                mangaRepository.getMangaById(mangaId).title
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
        },
        sourceProvider = { sourceId ->
            try {
                sourceManager.getOrStub(sourceId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
        },
        downloadLookup = { variant, mangaTitle, source ->
            withIOContext {
                downloadManager.isChapterDownloadedOnDisk(
                    chapterName = variant.rawName,
                    chapterScanlator = variant.scanlationGroup,
                    chapterUrl = variant.sourceChapterUrl.orEmpty(),
                    mangaTitle = mangaTitle,
                    source = source,
                )
            }
        },
    )

    override suspend fun isDownloaded(variant: ChapterVariant): Boolean {
        val mangaId = variant.mihonMangaId ?: return false
        if (variant.sourceChapterUrl.isNullOrBlank()) return false
        val mangaTitle = mangaTitleProvider(mangaId)?.takeIf(String::isNotBlank) ?: return false
        val source = sourceProvider(variant.sourceId) ?: return false
        return downloadLookup(variant, mangaTitle, source)
    }
}
