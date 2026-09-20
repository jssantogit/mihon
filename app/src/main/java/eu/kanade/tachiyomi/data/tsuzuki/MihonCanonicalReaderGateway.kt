package eu.kanade.tachiyomi.data.tsuzuki

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.download.service.CanonicalDownloadGateway
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.model.OperationalReaderChapter
import tachiyomi.domain.tsuzuki.reader.service.CanonicalReaderGateway
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MihonCanonicalReaderGateway(
    private val chapterRepository: ChapterRepository,
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val sourceTitleMappingRepository: SourceTitleMappingRepository,
    private val canonicalDownloadGateway: CanonicalDownloadGateway = NoCanonicalReaderDownloads,
) : CanonicalReaderGateway {

    override suspend fun materialize(
        variant: ChapterVariant,
        progress: CanonicalChapterProgress?,
    ): Result<OperationalReaderChapter> {
        return try {
            require(progress == null || progress.canonicalChapterId == variant.canonicalChapterId) {
                "Canonical progress does not belong to variant chapter"
            }

            val canonicalChapter = canonicalChapterRepository.getById(variant.canonicalChapterId)
                ?: error("Canonical chapter ${variant.canonicalChapterId} not found")
            val mapping = sourceTitleMappingRepository
                .getByCanonicalTitleId(canonicalChapter.canonicalTitleId)
                .firstOrNull { it.id == variant.sourceMappingId }
                ?: error("Source mapping ${variant.sourceMappingId} not found")

            require(mapping.sourceId == variant.sourceId) {
                "Variant source does not match its source mapping"
            }
            val downloadVariant = if (variant.mihonMangaId == null && mapping.mihonMangaId != null) {
                variant.copy(mihonMangaId = mapping.mihonMangaId)
            } else {
                variant
            }
            val isReadableOffline = mapping.availability == SourceMappingAvailability.UNAVAILABLE &&
                canonicalDownloadGateway.isDownloaded(downloadVariant)
            require(mapping.availability != SourceMappingAvailability.UNAVAILABLE || isReadableOffline) {
                "Source mapping ${mapping.id} is unavailable and variant is not downloaded"
            }
            if (variant.mihonMangaId != null && mapping.mihonMangaId != null) {
                require(variant.mihonMangaId == mapping.mihonMangaId) {
                    "Variant Mihon manga does not match its source mapping"
                }
            }

            val mangaId = variant.mihonMangaId ?: mapping.mihonMangaId
                ?: error("Source mapping ${mapping.id} is not materialized")
            val sourceUrl = variant.sourceChapterUrl
                ?.takeIf(String::isNotBlank)
                ?: variant.sourceChapterId.takeIf(String::isNotBlank)
                ?: error("Variant has no source-local chapter URL")

            val byStoredId = variant.mihonChapterId?.let { chapterId ->
                chapterRepository.getChapterById(chapterId)
            }
            val existing = byStoredId
                ?.takeIf { it.mangaId == mangaId && it.url == sourceUrl }
                ?: chapterRepository.getChapterByUrlAndMangaId(sourceUrl, mangaId)

            val canonicalResumePage = progress
                ?.takeIf { it.lastVariantId == variant.id }
                ?.lastPageRead
                ?: 0L

            val chapter = existing ?: Chapter.create().copy(
                mangaId = mangaId,
                url = sourceUrl,
                name = variant.rawName,
                scanlator = variant.scanlationGroup,
                read = progress?.read ?: false,
                lastPageRead = canonicalResumePage,
                chapterNumber = variant.rawNumberHint ?: -1.0,
                sourceOrder = variant.rawSourceOrder ?: 0L,
                dateUpload = variant.releaseDate ?: 0L,
                version = variant.version ?: 1L,
                memo = variant.rawSourceMetadata,
            ).let { newChapter ->
                chapterRepository.addAll(listOf(newChapter)).singleOrNull()
                    ?: error("Failed to materialize operational Mihon chapter")
            }

            if (progress != null) {
                val projectedLastPageRead = if (progress.lastVariantId == variant.id) {
                    progress.lastPageRead
                } else {
                    chapter.lastPageRead
                }
                if (chapter.read != progress.read || chapter.lastPageRead != projectedLastPageRead) {
                    chapterRepository.update(
                        ChapterUpdate(
                            id = chapter.id,
                            read = progress.read,
                            lastPageRead = projectedLastPageRead,
                        ),
                    )
                }
            }

            if (variant.mihonMangaId != mangaId || variant.mihonChapterId != chapter.id) {
                canonicalChapterRepository.upsertVariant(
                    variant.copy(
                        mihonMangaId = mangaId,
                        mihonChapterId = chapter.id,
                    ),
                )
            }

            Result.success(
                OperationalReaderChapter(
                    canonicalChapterId = variant.canonicalChapterId,
                    variantId = variant.id,
                    sourceMappingId = variant.sourceMappingId,
                    mihonMangaId = mangaId,
                    mihonChapterId = chapter.id,
                    sourceId = variant.sourceId,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }
}


private object NoCanonicalReaderDownloads : CanonicalDownloadGateway {
    override suspend fun isDownloaded(variant: ChapterVariant): Boolean = false
}
