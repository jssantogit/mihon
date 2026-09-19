package tachiyomi.domain.tsuzuki.reader.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.tsuzuki.chapter.interactor.SelectChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreparation
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReaderPreferenceRepository
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import tachiyomi.domain.tsuzuki.reader.service.CanonicalReaderGateway

@Inject
class PrepareCanonicalChapterForReader(
    private val selectChapterVariant: SelectChapterVariant,
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val canonicalReadingRepository: CanonicalReadingRepository,
    private val canonicalReaderPreferenceRepository: CanonicalReaderPreferenceRepository,
    private val canonicalReaderGateway: CanonicalReaderGateway,
) {

    suspend fun execute(
        canonicalChapterId: String,
        preferredLanguage: String? = null,
        allowFallbackOnce: Boolean = false,
    ): CanonicalReaderPreparation {
        return try {
            val chapter = canonicalChapterRepository.getById(canonicalChapterId)
                ?: return CanonicalReaderPreparation.Unavailable(canonicalChapterId)
            val selection = selectChapterVariant.execute(
                canonicalChapterId = canonicalChapterId,
                preferredLanguage = preferredLanguage,
            )
            val variant = selection.selected
                ?: return CanonicalReaderPreparation.Unavailable(canonicalChapterId)

            val automaticFallback = canonicalReaderPreferenceRepository
                .get(chapter.canonicalTitleId)
                ?.automaticFallback == true
            if (selection.requiresFallback && !allowFallbackOnce && !automaticFallback) {
                return CanonicalReaderPreparation.FallbackRequired(
                    canonicalTitleId = chapter.canonicalTitleId,
                    canonicalChapterId = canonicalChapterId,
                    preferredSourceMappingId = selection.preferredSourceMappingId,
                    fallbackVariant = variant,
                )
            }

            val progress = canonicalReadingRepository.getProgress(canonicalChapterId)
            canonicalReaderGateway.materialize(variant, progress).fold(
                onSuccess = { target ->
                    CanonicalReaderPreparation.Ready(
                        target = target,
                        usedFallback = selection.requiresFallback,
                    )
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    CanonicalReaderPreparation.Failed(canonicalChapterId, error)
                },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            CanonicalReaderPreparation.Failed(canonicalChapterId, error)
        }
    }

    suspend operator fun invoke(
        canonicalChapterId: String,
        preferredLanguage: String? = null,
    ): CanonicalReaderPreparation = execute(canonicalChapterId, preferredLanguage)
}
