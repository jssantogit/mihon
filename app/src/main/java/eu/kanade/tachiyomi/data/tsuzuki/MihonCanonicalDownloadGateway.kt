package eu.kanade.tachiyomi.data.tsuzuki

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.content.ContentOption
import tachiyomi.domain.tsuzuki.download.model.CanonicalDownloadArtifact
import tachiyomi.domain.tsuzuki.download.service.CanonicalDownloadGateway
import kotlin.time.Clock

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MihonCanonicalDownloadGateway internal constructor(
    private val mangaTitleProvider: suspend (Long) -> String?,
    private val sourceProvider: suspend (Long) -> Source?,
    private val downloadLookup: suspend (ChapterVariant, String, Source) -> Boolean,
    private val artifactLocator: suspend (ContentOption) -> CanonicalDownloadArtifact? = { null },
    private val downloadStarter: (Long) -> Unit = {},
    private val downloadCompletion: suspend (Long) -> Boolean = { false },
) : CanonicalDownloadGateway {

    @Inject
    constructor(
        mangaRepository: MangaRepository,
        chapterRepository: ChapterRepository,
        sourceManager: SourceManager,
        downloadManager: DownloadManager,
        downloadProvider: DownloadProvider,
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
        artifactLocator = { option ->
            locateArtifact(
                option = option,
                mangaRepository = mangaRepository,
                chapterRepository = chapterRepository,
                sourceManager = sourceManager,
                downloadProvider = downloadProvider,
            )
        },
        downloadStarter = downloadManager::startDownloadNow,
        downloadCompletion = { chapterId ->
            awaitDownloadCompletion(downloadManager, chapterId)
        },
    )

    override suspend fun isDownloaded(variant: ChapterVariant): Boolean {
        val mangaId = variant.mihonMangaId ?: return false
        if (variant.sourceChapterUrl.isNullOrBlank()) return false
        val mangaTitle = mangaTitleProvider(mangaId)?.takeIf(String::isNotBlank) ?: return false
        val source = sourceProvider(variant.sourceId) ?: return false
        return downloadLookup(variant, mangaTitle, source)
    }

    override suspend fun acquire(option: ContentOption): Result<CanonicalDownloadArtifact> {
        return try {
            val delivery = option.delivery as? ContentDelivery.Mihon
                ?: return Result.failure(
                    IllegalArgumentException("Mihon download gateway requires Mihon content delivery"),
                )

            artifactLocator(option)?.let { artifact ->
                return Result.success(artifact)
            }

            downloadStarter(delivery.chapterId)
            if (!downloadCompletion(delivery.chapterId)) {
                return Result.failure(
                    IllegalStateException("Operational chapter download did not complete"),
                )
            }

            val artifact = artifactLocator(option)
                ?: return Result.failure(
                    IllegalStateException("Completed chapter download could not be located"),
                )
            Result.success(artifact)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    companion object {
        private suspend fun locateArtifact(
            option: ContentOption,
            mangaRepository: MangaRepository,
            chapterRepository: ChapterRepository,
            sourceManager: SourceManager,
            downloadProvider: DownloadProvider,
        ): CanonicalDownloadArtifact? {
            val delivery = option.delivery as? ContentDelivery.Mihon ?: return null
            val chapter = chapterRepository.getChapterById(delivery.chapterId) ?: return null
            if (chapter.mangaId != delivery.mangaId) return null

            val manga = mangaRepository.getMangaById(delivery.mangaId)
            if (manga.source != delivery.sourceId) return null
            val source = sourceManager.getOrStub(delivery.sourceId)
            val file = downloadProvider.findChapterDir(
                chapterName = chapter.name,
                chapterScanlator = chapter.scanlator,
                chapterUrl = chapter.url,
                mangaTitle = manga.title,
                source = source,
            ) ?: return null

            return CanonicalDownloadArtifact(
                canonicalChapterId = option.canonicalChapterId,
                localUri = file.uri.toString(),
                format = canonicalFormat(file.name.orEmpty(), file.isDirectory),
                originatingAddonId = option.addonId,
                originatingOptionKey = option.key,
                completedAt = Clock.System.now().toEpochMilliseconds(),
                checksum = null,
            )
        }

        private suspend fun awaitDownloadCompletion(
            downloadManager: DownloadManager,
            chapterId: Long,
        ): Boolean {
            val download = downloadManager.getQueuedDownloadOrNull(chapterId) ?: return false
            val terminal = when (download.status) {
                Download.State.DOWNLOADED,
                Download.State.ERROR,
                -> download.status
                else -> download.statusFlow.first { state ->
                    state == Download.State.DOWNLOADED || state == Download.State.ERROR
                }
            }
            return terminal == Download.State.DOWNLOADED
        }

        private fun canonicalFormat(
            fileName: String,
            isDirectory: Boolean,
        ): String {
            if (isDirectory) return "DIRECTORY"
            return when (fileName.substringAfterLast('.', "").uppercase()) {
                "CBZ" -> "CBZ"
                "CBR" -> "CBR"
                "ZIP" -> "ZIP"
                "RAR" -> "RAR"
                "EPUB" -> "EPUB"
                else -> "ARCHIVE"
            }
        }
    }
}
