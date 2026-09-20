package eu.kanade.tachiyomi.data.tsuzuki

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.DownloadManager
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.download.service.CanonicalDownloadGateway

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MihonCanonicalDownloadGateway internal constructor(
    private val mangaTitleProvider: suspend (Long) -> String?,
    private val downloadLookup: (ChapterVariant, String) -> Boolean,
) : CanonicalDownloadGateway {

    @Inject
    constructor(
        mangaRepository: MangaRepository,
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
        downloadLookup = { variant, mangaTitle ->
            downloadManager.isChapterDownloaded(
                chapterName = variant.rawName,
                chapterScanlator = variant.scanlationGroup,
                chapterUrl = variant.sourceChapterUrl.orEmpty(),
                mangaTitle = mangaTitle,
                sourceId = variant.sourceId,
            )
        },
    )

    override suspend fun isDownloaded(variant: ChapterVariant): Boolean {
        val mangaId = variant.mihonMangaId ?: return false
        if (variant.sourceChapterUrl.isNullOrBlank()) return false
        val mangaTitle = mangaTitleProvider(mangaId)?.takeIf(String::isNotBlank) ?: return false
        return downloadLookup(variant, mangaTitle)
    }
}
