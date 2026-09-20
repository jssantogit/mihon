package tachiyomi.domain.tsuzuki.download.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.download.model.CanonicalChapterDownloadState
import tachiyomi.domain.tsuzuki.download.service.CanonicalDownloadGateway

@Inject
class GetCanonicalChapterDownloadState(
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val canonicalDownloadGateway: CanonicalDownloadGateway,
) {

    suspend fun execute(canonicalChapterId: String): CanonicalChapterDownloadState {
        val variants = canonicalChapterRepository
            .getVariantsByCanonicalChapterId(canonicalChapterId)
        val downloaded = variants.filter { variant ->
            canonicalDownloadGateway.isDownloaded(variant)
        }
        return CanonicalChapterDownloadState(
            canonicalChapterId = canonicalChapterId,
            downloadedVariants = downloaded,
        )
    }

    suspend operator fun invoke(canonicalChapterId: String): CanonicalChapterDownloadState =
        execute(canonicalChapterId)
}
