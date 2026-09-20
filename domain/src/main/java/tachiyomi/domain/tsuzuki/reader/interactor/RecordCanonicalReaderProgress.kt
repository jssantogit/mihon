package tachiyomi.domain.tsuzuki.reader.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistoryUpdate
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import tachiyomi.domain.tsuzuki.reader.service.CanonicalReaderCompatibilityGateway
import kotlin.time.Clock

class RecordCanonicalReaderProgress internal constructor(
    private val repository: CanonicalReadingRepository,
    private val compatibilityGateway: CanonicalReaderCompatibilityGateway,
    private val clock: () -> Long,
) {

    internal constructor(
        repository: CanonicalReadingRepository,
        clock: () -> Long,
    ) : this(
        repository = repository,
        compatibilityGateway = NoopCompatibilityGateway,
        clock = clock,
    )

    @Inject
    constructor(
        repository: CanonicalReadingRepository,
        compatibilityGateway: CanonicalReaderCompatibilityGateway,
    ) : this(
        repository = repository,
        compatibilityGateway = compatibilityGateway,
        clock = { Clock.System.now().toEpochMilliseconds() },
    )

    suspend fun recordPage(
        canonicalChapterId: String,
        variantId: String,
        pageIndex: Int,
        completed: Boolean,
        mihonChapterId: Long? = null,
    ) {
        require(pageIndex >= 0) { "pageIndex must not be negative" }
        val existing = repository.getProgress(canonicalChapterId)
        val progress = CanonicalChapterProgress(
            canonicalChapterId = canonicalChapterId,
            read = existing?.read == true || completed,
            lastPageRead = pageIndex.toLong(),
            lastVariantId = variantId,
            updatedAt = clock(),
        )
        repository.upsertProgress(progress)

        if (mihonChapterId != null) {
            compatibilityGateway.projectProgress(
                mihonChapterId = mihonChapterId,
                read = progress.read,
                lastPageRead = progress.lastPageRead,
            )
        }
    }

    suspend fun recordHistory(
        canonicalChapterId: String,
        variantId: String?,
        sessionReadDuration: Long,
        mihonChapterId: Long? = null,
    ) {
        require(sessionReadDuration >= 0L) { "sessionReadDuration must not be negative" }
        val readAt = clock()
        repository.recordHistory(
            CanonicalChapterHistoryUpdate(
                canonicalChapterId = canonicalChapterId,
                variantId = variantId,
                readAt = readAt,
                sessionReadDuration = sessionReadDuration,
            ),
        )

        if (mihonChapterId != null) {
            compatibilityGateway.projectHistory(
                mihonChapterId = mihonChapterId,
                readAt = readAt,
                sessionReadDuration = sessionReadDuration,
            )
        }
    }

    private object NoopCompatibilityGateway : CanonicalReaderCompatibilityGateway {
        override suspend fun projectProgress(
            mihonChapterId: Long,
            read: Boolean,
            lastPageRead: Long,
        ) = Unit

        override suspend fun projectHistory(
            mihonChapterId: Long,
            readAt: Long,
            sessionReadDuration: Long,
        ) = Unit
    }
}
