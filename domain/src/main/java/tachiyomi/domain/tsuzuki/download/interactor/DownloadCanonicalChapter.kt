package tachiyomi.domain.tsuzuki.download.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.content.ContentOption
import tachiyomi.domain.tsuzuki.content.interactor.ResolveChapterContent
import tachiyomi.domain.tsuzuki.content.repository.ContentPreferenceRepository
import tachiyomi.domain.tsuzuki.download.model.CanonicalDownloadPreparation
import tachiyomi.domain.tsuzuki.download.repository.CanonicalDownloadRepository
import tachiyomi.domain.tsuzuki.download.service.CanonicalDownloadGateway

@Inject
class DownloadCanonicalChapter(
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val contentPreferenceRepository: ContentPreferenceRepository,
    private val resolveChapterContent: ResolveChapterContent,
    private val canonicalDownloadRepository: CanonicalDownloadRepository,
    private val canonicalDownloadGateway: CanonicalDownloadGateway,
) {

    suspend fun execute(
        canonicalChapterId: String,
        selectedOption: ContentOption? = null,
    ): CanonicalDownloadPreparation {
        return try {
            canonicalDownloadRepository.get(canonicalChapterId)?.let { artifact ->
                return CanonicalDownloadPreparation.Complete(
                    canonicalChapterId = canonicalChapterId,
                    artifact = artifact,
                    reused = true,
                )
            }

            val chapter = canonicalChapterRepository.getById(canonicalChapterId)
                ?: return CanonicalDownloadPreparation.Unavailable(canonicalChapterId)

            if (selectedOption != null) {
                require(selectedOption.canonicalChapterId == canonicalChapterId) {
                    "Selected content option does not belong to canonical chapter"
                }
                return acquire(canonicalChapterId, selectedOption)
            }

            val options = resolveChapterContent.resolveOptions(
                canonicalTitleId = chapter.canonicalTitleId,
                canonicalChapterId = canonicalChapterId,
            )
            if (options.isEmpty()) {
                return CanonicalDownloadPreparation.Unavailable(canonicalChapterId)
            }

            val preferredAddonId = contentPreferenceRepository
                .get(chapter.canonicalTitleId)
                ?.preferredAddonId
            val preferred = preferredAddonId?.let { addonId ->
                options.firstOrNull { it.addonId == addonId }
            }

            if (preferred == null) {
                return CanonicalDownloadPreparation.SelectionRequired(
                    canonicalTitleId = chapter.canonicalTitleId,
                    canonicalChapterId = canonicalChapterId,
                    options = options,
                    preferredAddonId = preferredAddonId,
                )
            }

            acquire(canonicalChapterId, preferred)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            CanonicalDownloadPreparation.Failed(canonicalChapterId, error)
        }
    }

    private suspend fun acquire(
        canonicalChapterId: String,
        option: ContentOption,
    ): CanonicalDownloadPreparation {
        return canonicalDownloadGateway.acquire(option).fold(
            onSuccess = { artifact ->
                require(artifact.canonicalChapterId == canonicalChapterId) {
                    "Acquired artifact does not belong to canonical chapter"
                }
                canonicalDownloadRepository.upsert(artifact)
                CanonicalDownloadPreparation.Complete(
                    canonicalChapterId = canonicalChapterId,
                    artifact = artifact,
                    reused = false,
                )
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                CanonicalDownloadPreparation.Failed(canonicalChapterId, error)
            },
        )
    }

    suspend operator fun invoke(
        canonicalChapterId: String,
    ): CanonicalDownloadPreparation = execute(canonicalChapterId)
}
