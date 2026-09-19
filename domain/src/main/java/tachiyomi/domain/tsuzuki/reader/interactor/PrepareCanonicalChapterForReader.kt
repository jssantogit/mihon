package tachiyomi.domain.tsuzuki.reader.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.tsuzuki.chapter.interactor.SelectChapterVariant
import tachiyomi.domain.tsuzuki.reader.model.OperationalReaderChapter
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import tachiyomi.domain.tsuzuki.reader.service.CanonicalReaderGateway

@Inject
class PrepareCanonicalChapterForReader(
    private val selectChapterVariant: SelectChapterVariant,
    private val canonicalReadingRepository: CanonicalReadingRepository,
    private val canonicalReaderGateway: CanonicalReaderGateway,
) {

    suspend fun execute(
        canonicalChapterId: String,
        preferredLanguage: String? = null,
    ): Result<OperationalReaderChapter> {
        return try {
            val selection = selectChapterVariant.execute(
                canonicalChapterId = canonicalChapterId,
                preferredLanguage = preferredLanguage,
            )
            val variant = selection.selected
                ?: return Result.failure(IllegalStateException("No readable variant is available"))

            val progress = canonicalReadingRepository.getProgress(canonicalChapterId)
            canonicalReaderGateway.materialize(variant, progress).getOrElse { error ->
                if (error is CancellationException) throw error
                return Result.failure(error)
            }.let(Result.Companion::success)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    suspend operator fun invoke(
        canonicalChapterId: String,
        preferredLanguage: String? = null,
    ): Result<OperationalReaderChapter> = execute(canonicalChapterId, preferredLanguage)
}
