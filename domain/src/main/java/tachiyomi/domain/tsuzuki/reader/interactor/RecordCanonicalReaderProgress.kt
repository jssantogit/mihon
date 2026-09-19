package tachiyomi.domain.tsuzuki.reader.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistoryUpdate
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import kotlin.time.Clock

class RecordCanonicalReaderProgress internal constructor(
    private val repository: CanonicalReadingRepository,
    private val clock: () -> Long,
) {

    @Inject
    constructor(repository: CanonicalReadingRepository) : this(
        repository = repository,
        clock = { Clock.System.now().toEpochMilliseconds() },
    )

    suspend fun recordPage(
        canonicalChapterId: String,
        pageIndex: Int,
        completed: Boolean,
    ) {
        require(pageIndex >= 0) { "pageIndex must not be negative" }
        val existing = repository.getProgress(canonicalChapterId)
        repository.upsertProgress(
            CanonicalChapterProgress(
                canonicalChapterId = canonicalChapterId,
                read = existing?.read == true || completed,
                lastPageRead = pageIndex.toLong(),
                updatedAt = clock(),
            ),
        )
    }

    suspend fun recordHistory(
        canonicalChapterId: String,
        variantId: String?,
        sessionReadDuration: Long,
    ) {
        require(sessionReadDuration >= 0L) { "sessionReadDuration must not be negative" }
        repository.recordHistory(
            CanonicalChapterHistoryUpdate(
                canonicalChapterId = canonicalChapterId,
                variantId = variantId,
                readAt = clock(),
                sessionReadDuration = sessionReadDuration,
            ),
        )
    }
}
