package tachiyomi.domain.tsuzuki.reader.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.content.ContentOption
import tachiyomi.domain.tsuzuki.content.interactor.ResolveChapterContent
import tachiyomi.domain.tsuzuki.content.model.ContentResolution
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreparation
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import tachiyomi.domain.tsuzuki.reader.service.ChapterContentPreparer

@Inject
class PrepareCanonicalChapterForReader(
    private val resolveChapterContent: ResolveChapterContent,
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val canonicalReadingRepository: CanonicalReadingRepository,
    private val chapterContentPreparer: ChapterContentPreparer,
) {

    suspend fun execute(
        canonicalChapterId: String,
        selectedOption: ContentOption? = null,
    ): CanonicalReaderPreparation {
        return try {
            val chapter = canonicalChapterRepository.getById(canonicalChapterId)
                ?: return CanonicalReaderPreparation.Unavailable(canonicalChapterId)

            val resolution = if (selectedOption != null) {
                require(selectedOption.canonicalChapterId == canonicalChapterId) {
                    "Selected content option does not belong to canonical chapter"
                }
                ContentResolution.Direct(
                    option = selectedOption,
                    usedFallback = false,
                )
            } else {
                resolveChapterContent.execute(
                    canonicalTitleId = chapter.canonicalTitleId,
                    canonicalChapterId = canonicalChapterId,
                )
            }

            when (resolution) {
                is ContentResolution.Direct -> prepare(
                    canonicalChapterId = canonicalChapterId,
                    option = resolution.option,
                    usedFallback = resolution.usedFallback,
                )

                is ContentResolution.NeedsSelection -> CanonicalReaderPreparation.SelectionRequired(
                    canonicalTitleId = chapter.canonicalTitleId,
                    canonicalChapterId = canonicalChapterId,
                    options = resolution.options,
                    preferredAddonId = resolution.preferredAddonId,
                    preferredUnavailable = resolution.preferredUnavailable,
                )

                ContentResolution.Unavailable -> CanonicalReaderPreparation.Unavailable(canonicalChapterId)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            CanonicalReaderPreparation.Failed(canonicalChapterId, error)
        }
    }

    private suspend fun prepare(
        canonicalChapterId: String,
        option: ContentOption,
        usedFallback: Boolean,
    ): CanonicalReaderPreparation {
        val progress = canonicalReadingRepository.getProgress(canonicalChapterId)
        return chapterContentPreparer.prepare(option, progress).fold(
            onSuccess = { target ->
                CanonicalReaderPreparation.Ready(
                    canonicalChapterId = canonicalChapterId,
                    target = target,
                    usedFallback = usedFallback,
                )
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                CanonicalReaderPreparation.Failed(canonicalChapterId, error)
            },
        )
    }

    suspend operator fun invoke(
        canonicalChapterId: String,
    ): CanonicalReaderPreparation = execute(canonicalChapterId)
}
