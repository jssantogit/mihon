package tachiyomi.domain.tsuzuki.reader.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttribute
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticTrace
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticWorkflow
import tachiyomi.domain.tsuzuki.diagnostics.NoOpStructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder
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
    private val structuredDiagnostics: StructuredDiagnosticRecorder,
) {

    internal constructor(
        repository: CanonicalReadingRepository,
        clock: () -> Long,
    ) : this(
        repository = repository,
        compatibilityGateway = NoopCompatibilityGateway,
        chapterUpdateStateRepository = NoopChapterUpdateStateRepository,
        clock = clock,
        structuredDiagnostics = NoOpStructuredDiagnosticRecorder,
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
        structuredDiagnostics = NoOpStructuredDiagnosticRecorder,
    )

    @Inject
    constructor(
        repository: CanonicalReadingRepository,
        compatibilityGateway: CanonicalReaderCompatibilityGateway,
        chapterUpdateStateRepository: ChapterUpdateStateRepository,
        structuredDiagnostics: StructuredDiagnosticRecorder,
    ) : this(
        repository = repository,
        compatibilityGateway = compatibilityGateway,
        chapterUpdateStateRepository = chapterUpdateStateRepository,
        clock = { Clock.System.now().toEpochMilliseconds() },
        structuredDiagnostics = structuredDiagnostics,
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

        if (completed || pageIndex % PROGRESS_DIAGNOSTIC_INTERVAL == 0) {
            val trace = DiagnosticTrace.start(
                recorder = structuredDiagnostics,
                workflow = DiagnosticWorkflow.READER_OPEN,
                subsystem = DiagnosticSubsystem.READER,
            )
            trace.event(
                subsystem = DiagnosticSubsystem.READER,
                name = DiagnosticEventName.READER_PROGRESS_RECORDED,
                stage = DiagnosticStage.WRITE,
                outcome = DiagnosticOutcome.SUCCEEDED,
                attributes = mapOf(
                    DiagnosticAttribute.PAGE_INDEX to DiagnosticAttributeValue.Number(pageIndex.toLong()),
                    DiagnosticAttribute.COMPLETED to DiagnosticAttributeValue.Flag(completed),
                ),
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
        val trace = DiagnosticTrace.start(
            recorder = structuredDiagnostics,
            workflow = DiagnosticWorkflow.READER_OPEN,
            subsystem = DiagnosticSubsystem.READER,
        )
        trace.event(
            subsystem = DiagnosticSubsystem.READER,
            name = DiagnosticEventName.READER_PROGRESS_RECORDED,
            stage = DiagnosticStage.WRITE,
            outcome = DiagnosticOutcome.SUCCEEDED,
            durationMillis = sessionReadDuration.coerceAtMost(MAX_DIAGNOSTIC_DURATION_MILLIS),
        )
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

    private companion object {
        const val PROGRESS_DIAGNOSTIC_INTERVAL = 10
        const val MAX_DIAGNOSTIC_DURATION_MILLIS = 24L * 60 * 60 * 1_000
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
