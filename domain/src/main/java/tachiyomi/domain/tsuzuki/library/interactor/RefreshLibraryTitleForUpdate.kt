package tachiyomi.domain.tsuzuki.library.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.tsuzuki.chapter.evidence.RefreshChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.model.ChapterReconciliationReport
import tachiyomi.domain.tsuzuki.library.model.LibraryTitle
import tachiyomi.domain.tsuzuki.updates.interactor.RecordNewCanonicalChapters

@Inject
class RefreshLibraryTitleForUpdate(
    private val refreshChapterEvidence: RefreshChapterEvidence,
    private val recordNewCanonicalChapters: RecordNewCanonicalChapters,
) {

    suspend fun execute(
        libraryTitle: LibraryTitle,
    ): Result<ChapterReconciliationReport?> {
        val refreshed = refreshChapterEvidence.execute(libraryTitle.id)
        val refreshError = refreshed.exceptionOrNull()
        if (refreshError != null) return Result.failure(refreshError)

        return try {
            recordNewCanonicalChapters.execute(libraryTitle.id)
            // Compatibility return type for existing update callers. The new
            // evidence-led path no longer produces a source-inventory report.
            Result.success(null)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }
}
