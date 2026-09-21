package eu.kanade.tachiyomi.data.tsuzuki

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.content.ContentOption
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent
import tachiyomi.domain.tsuzuki.reader.service.CanonicalReaderGateway
import tachiyomi.domain.tsuzuki.reader.service.ChapterContentPreparer

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MihonChapterContentPreparer(
    private val canonicalReaderGateway: CanonicalReaderGateway,
) : ChapterContentPreparer {

    override suspend fun prepare(
        option: ContentOption,
        progress: CanonicalChapterProgress?,
    ): Result<PreparedChapterContent> {
        return try {
            require(progress == null || progress.canonicalChapterId == option.canonicalChapterId) {
                "Canonical progress does not belong to content option chapter"
            }

            when (val delivery = option.delivery) {
                is ContentDelivery.Mihon -> {
                    canonicalReaderGateway.materialize(
                        canonicalChapterId = option.canonicalChapterId,
                        delivery = delivery,
                        progress = progress,
                    ).map { target ->
                        PreparedChapterContent.MihonOperational(
                            mangaId = target.mihonMangaId,
                            chapterId = target.mihonChapterId,
                            sourceId = target.sourceId,
                        )
                    }
                }

                is ContentDelivery.LocalArchive -> Result.success(
                    PreparedChapterContent.LocalArchive(delivery.uri),
                )

                is ContentDelivery.LocalDirectory -> Result.success(
                    PreparedChapterContent.LocalDirectory(delivery.uri),
                )

                is ContentDelivery.Torrent -> Result.failure(
                    UnsupportedOperationException(
                        "Torrent preparation is not implemented in the Mihon compatibility layer",
                    ),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }
}
