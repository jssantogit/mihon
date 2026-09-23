package eu.kanade.tachiyomi.data.tsuzuki

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.model.OperationalReaderChapter
import tachiyomi.domain.tsuzuki.reader.service.CanonicalReaderGateway

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MihonCanonicalReaderGateway(
    private val chapterRepository: ChapterRepository,
) : CanonicalReaderGateway {

    override suspend fun materialize(
        canonicalChapterId: String,
        delivery: ContentDelivery.Mihon,
        progress: CanonicalChapterProgress?,
    ): Result<OperationalReaderChapter> {
        return try {
            require(progress == null || progress.canonicalChapterId == canonicalChapterId) {
                "Canonical progress does not belong to requested chapter"
            }

            val chapter = chapterRepository.getChapterById(delivery.chapterId)
                ?: error("Operational Mihon chapter " + delivery.chapterId + " not found")
            require(chapter.mangaId == delivery.mangaId) {
                "Operational Mihon chapter does not belong to delivery manga"
            }

            if (progress != null && (chapter.read != progress.read || chapter.lastPageRead != progress.lastPageRead)) {
                chapterRepository.update(
                    ChapterUpdate(
                        id = chapter.id,
                        read = progress.read,
                        lastPageRead = progress.lastPageRead,
                    ),
                )
            }

            Result.success(
                OperationalReaderChapter(
                    canonicalChapterId = canonicalChapterId,
                    variantId = progress?.lastVariantId.orEmpty(),
                    sourceMappingId = "",
                    mihonMangaId = delivery.mangaId,
                    mihonChapterId = delivery.chapterId,
                    sourceId = delivery.sourceId,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }
}
