package tachiyomi.domain.tsuzuki.chapter.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariantSelection

/**
 * Finds a per-chapter alternative without changing the title's preferred
 * source mapping or any read/progress state.
 */
@Inject
class FindChapterFallback(
    private val selectChapterVariant: SelectChapterVariant,
) {

    suspend fun execute(
        canonicalChapterId: String,
        unavailableSourceMappingId: String,
        preferredLanguage: String? = null,
    ): ChapterVariantSelection {
        return selectChapterVariant.execute(
            canonicalChapterId = canonicalChapterId,
            preferredLanguage = preferredLanguage,
            excludedSourceMappingIds = setOf(unavailableSourceMappingId),
        )
    }

    suspend operator fun invoke(
        canonicalChapterId: String,
        unavailableSourceMappingId: String,
        preferredLanguage: String? = null,
    ): ChapterVariantSelection = execute(
        canonicalChapterId = canonicalChapterId,
        unavailableSourceMappingId = unavailableSourceMappingId,
        preferredLanguage = preferredLanguage,
    )
}
