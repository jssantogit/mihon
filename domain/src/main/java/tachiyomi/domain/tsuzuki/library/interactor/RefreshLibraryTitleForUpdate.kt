package tachiyomi.domain.tsuzuki.library.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.chapter.interactor.RefreshCanonicalChapters
import tachiyomi.domain.tsuzuki.chapter.model.ChapterReconciliationReport
import tachiyomi.domain.tsuzuki.library.model.LibraryTitle
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability

@Inject
class RefreshLibraryTitleForUpdate(
    private val refreshCanonicalChapters: RefreshCanonicalChapters,
) {

    suspend fun execute(
        libraryTitle: LibraryTitle,
    ): Result<ChapterReconciliationReport?> {
        val mappingIds = libraryTitle.sources
            .filter {
                it.mihonMangaId != null &&
                    it.availability != SourceMappingAvailability.UNAVAILABLE
            }
            .map { it.id }

        if (mappingIds.isEmpty()) {
            return Result.success(null)
        }

        return refreshCanonicalChapters
            .execute(
                canonicalTitleId = libraryTitle.id,
                mappingIds = mappingIds,
            )
            .map { it }
    }
}
