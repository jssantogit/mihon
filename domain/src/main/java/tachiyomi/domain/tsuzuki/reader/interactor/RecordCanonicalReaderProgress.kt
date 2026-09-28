package tachiyomi.domain.tsuzuki.reader.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistoryUpdate
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import tachiyomi.domain.tsuzuki.reader.service.CanonicalReaderCompatibilityGateway
import tachiyomi.domain.tsuzuki.updates.repository.ChapterUpdateStateRepository
import kotlin.time.Clock

class RecordCanonicalReaderProgress internal constructor(
    private val repository: CanonicalReadingRepository,
    private val compatibilityGateway: CanonicalReaderCompatibilityGateway,
    private val chapterUpdateStateRepository: ChapterUpdateStateRepository,
    private val clock: () -> Long,
) {

    internal constructor(
        repository: CanonicalReadingRepository,
        clock: () -> Long,
    ) : this(
        repository = repository,
        compatibilityGateway = NoopCompatibilityGateway,
        chapterUpdateStateRepository = NoopChapterUpdateStateRepository,
        clock = clock,
    )

    internal constructor(
        repository: CanonicalReadingRepository,
        compatibilityGateway: CanonicalReaderCompatibilityGateway,
        clock: () -> Long,
    ) : this(
        repository = repository,
        compatibilityGateway = compatibilityGateway,
        chapterUpdateStateRepository = NoopChapterUpdateStateRepository,
        clock = clock,
    )

    @Inject
    constructor(
        repository: CanonicalReadingRepository,
        compatibilityGateway: CanonicalReaderCompatibilityGateway,
        chapterUpdateStateRepository: ChapterUpdateStateRepository,
    ) : this(
        repository = repository,
        compatibilityGateway = compatibilityGateway,
        chapterUpdateStateRepository = chapterUpdateStateRepository,
        clock = { Clock.System.now().toEpochMilliseconds() },
    )

    suspend fun recordPage(
        canonicalChapterId: String,
        pageIndex: Int,
        completed: Boolean,
        mihonChapterId: Long? = null,
    ) {
        recordPageInternal(
            canonicalChapterId = canonicalChapterId,
            legacyVariantId = null,
            pageIndex = pageIndex,
            completed = completed,
            mihonChapterId = mihonChapterId,
        )
    }

    suspend fun recordPage(
        canonicalChapterId: String,
        variantId: String,
        pageIndex: Int,
        completed: Boolean,
        mihonChapterId: Long? = null,
    ) {
        recordPageInternal(
            canonicalChapterId = canonicalChapterId,
            legacyVariantId = variantId,
            pageIndex = pageIndex,
            completed = completed,
            mihonChapterId = mihonChapterId,
        )
    }

    private suspend fun recordPageInternal(
        canonicalChapterId: String,
        legacyVariantId: String?,
        pageIndex: Int,
        completed: Boolean,
        mihonChapterId: Long?,
    ) {
        require(pageIndex >= 0) { "pageIndex must not be negative" }
        val existing = repository.getProgress(canonicalChapterId)
        val updatedAt = clock()
        val progress = CanonicalChapterProgress(
            canonicalChapterId = canonicalChapterId,
            read = existing?.read == true || completed,
            lastPageRead = pageIndex.toLong(),
            lastVariantId = legacyVariantId,
            updatedAt = updatedAt,
        )
        if (mihonChapterId == null) {
            repository.upsertProgress(progress)
        } else {
            repository.recordProgressWithProjection(progress, mihonChapterId)
        }

        if (completed && existing?.read != true) {
            chapterUpdateStateRepository.acknowledge(
                canonicalChapterId = canonicalChapterId,
                acknowledgedAt = updatedAt,
            )
        }

        if (mihonChapterId != null) flushDurableProjection()
    }

    suspend fun recordHistory(
        canonicalChapterId: String,
        variantId: String?,
        sessionReadDuration: Long,
        mihonChapterId: Long? = null,
    ) {
        require(sessionReadDuration >= 0L) { "sessionReadDuration must not be negative" }
        val readAt = clock()
        val history = CanonicalChapterHistoryUpdate(
            canonicalChapterId = canonicalChapterId,
            variantId = variantId,
            readAt = readAt,
            sessionReadDuration = sessionReadDuration,
        )
        if (mihonChapterId == null) {
            repository.recordHistory(history)
        } else {
            repository.recordHistoryWithProjection(history, mihonChapterId)
            flushDurableProjection()
        }
    }

    private suspend fun flushDurableProjection() {
        try {
            compatibilityGateway.flushPendingProjections()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Canonical state and the outbox were committed together. The next
            // Reader checkpoint or application start can safely retry.
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

    private object NoopChapterUpdateStateRepository : ChapterUpdateStateRepository {
        override suspend fun getByCanonicalTitleId(
            canonicalTitleId: String,
        ) = emptyList<tachiyomi.domain.tsuzuki.updates.repository.ChapterUpdateState>()

        override suspend fun getUnacknowledgedByCanonicalTitleId(
            canonicalTitleId: String,
        ) = emptyList<tachiyomi.domain.tsuzuki.updates.repository.ChapterUpdateState>()

        override suspend fun upsert(
            state: tachiyomi.domain.tsuzuki.updates.repository.ChapterUpdateState,
        ) = Unit

        override suspend fun acknowledge(
            canonicalChapterId: String,
            acknowledgedAt: Long,
        ) = Unit

        override suspend fun delete(canonicalChapterId: String) = Unit
    }
}
