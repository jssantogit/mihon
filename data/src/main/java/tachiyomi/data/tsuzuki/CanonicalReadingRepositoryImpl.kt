package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistory
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistoryUpdate
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import java.util.Date

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CanonicalReadingRepositoryImpl(
    private val database: Database,
) : CanonicalReadingRepository {

    override suspend fun getProgress(canonicalChapterId: String): CanonicalChapterProgress? {
        return database.tsuzuki_chapter_progressQueries
            .getTsuzukiChapterProgress(canonicalChapterId, ::mapProgress)
            .awaitAsOneOrNull()
    }

    override fun observeProgress(canonicalChapterId: String): Flow<CanonicalChapterProgress?> {
        return database.tsuzuki_chapter_progressQueries
            .observeTsuzukiChapterProgress(canonicalChapterId, ::mapProgress)
            .subscribeToList()
            .map { it.firstOrNull() }
    }

    override suspend fun getProgressByCanonicalTitleId(
        canonicalTitleId: String,
    ): List<CanonicalChapterProgress> {
        return database.tsuzuki_chapter_progressQueries
            .getTsuzukiChapterProgressByTitle(canonicalTitleId, ::mapProgress)
            .awaitAsList()
    }

    override fun observeProgressByCanonicalTitleId(
        canonicalTitleId: String,
    ): Flow<List<CanonicalChapterProgress>> {
        return database.tsuzuki_chapter_progressQueries
            .observeTsuzukiChapterProgressByTitle(canonicalTitleId, ::mapProgress)
            .subscribeToList()
    }

    override suspend fun upsertProgress(progress: CanonicalChapterProgress) {
        upsertProgressInternal(progress)
    }

    override suspend fun recordProgressWithProjection(
        progress: CanonicalChapterProgress,
        mihonChapterId: Long,
    ) {
        require(mihonChapterId > 0L) { "An operational chapter requires a positive Mihon ID" }
        database.transaction {
            upsertProgressInternal(progress)
            database.tsuzuki_mihon_projection_queueQueries.enqueueTsuzukiMihonProgress(
                canonicalChapterId = progress.canonicalChapterId,
                mihonChapterId = mihonChapterId,
                read = progress.read,
                lastPageRead = progress.lastPageRead,
                updatedAt = progress.updatedAt,
            )
        }
    }

    override suspend fun getHistory(canonicalChapterId: String): CanonicalChapterHistory? {
        return database.tsuzuki_chapter_historyQueries
            .getTsuzukiChapterHistory(canonicalChapterId, ::mapHistory)
            .awaitAsOneOrNull()
    }

    override suspend fun recordHistory(update: CanonicalChapterHistoryUpdate) {
        recordHistoryInternal(update)
    }

    override suspend fun recordHistoryWithProjection(
        update: CanonicalChapterHistoryUpdate,
        mihonChapterId: Long,
    ) {
        require(mihonChapterId > 0L) { "An operational chapter requires a positive Mihon ID" }
        database.transaction {
            recordHistoryInternal(update)
            database.tsuzuki_mihon_projection_queueQueries.enqueueTsuzukiMihonHistory(
                canonicalChapterId = update.canonicalChapterId,
                mihonChapterId = mihonChapterId,
                sessionReadDuration = update.sessionReadDuration,
                readAt = update.readAt,
            )
        }
    }

    override suspend fun recordCheckpoint(
        progress: CanonicalChapterProgress,
        history: CanonicalChapterHistoryUpdate?,
    ) {
        require(history == null || history.canonicalChapterId == progress.canonicalChapterId) {
            "Progress and history must belong to the same canonical chapter"
        }
        database.transaction {
            upsertProgressInternal(progress)
            history?.let { recordHistoryInternal(it) }
        }
    }

    override suspend fun drainPendingProjections(limit: Int): Int =
        drainPendingProjectionsAt(limit, System.currentTimeMillis())

    /**
     * Internal deterministic clock/failure seam for database integration tests.
     * Legacy writes and acknowledgement share one transaction. A simulated crash
     * between those steps cannot replay an additive history duration twice.
     */
    internal suspend fun drainPendingProjectionsAt(
        limit: Int,
        nowMillis: Long,
        beforeAcknowledge: () -> Unit = {},
    ): Int {
        require(limit in 1..64) { "Projection drain must be bounded" }
        val queue = database.tsuzuki_mihon_projection_queueQueries
        val due = queue.listDueTsuzukiMihonProjections(nowMillis, limit.toLong()) { chapterId, mihonId ->
            chapterId to mihonId
        }.awaitAsList()
        var processed = 0
        due.forEach { (chapterId, mihonId) ->
            var attempted: ProjectionRow? = null
            try {
                val applied = database.transactionWithResult {
                    val row = loadProjection(chapterId, mihonId) ?: return@transactionWithResult false
                    if (row.nextRetryAt > nowMillis || (!row.pendingProgress && !row.pendingHistory)) {
                        return@transactionWithResult false
                    }
                    attempted = row
                    check(
                        database.chaptersQueries.getChapterById(mihonId).awaitAsOneOrNull() != null,
                    ) { "Operational chapter no longer exists" }
                    if (row.pendingProgress) {
                        database.chaptersQueries.projectTsuzukiProgress(
                            read = checkNotNull(row.read),
                            lastPageRead = checkNotNull(row.page),
                            mihonChapterId = mihonId,
                        )
                    }
                    if (row.pendingHistory) {
                        database.historyQueries.projectTsuzukiHistory(
                            mihonChapterId = mihonId,
                            readAt = Date(checkNotNull(row.historyReadAt)),
                            duration = row.pendingDuration,
                        )
                    }
                    beforeAcknowledge()
                    queue.acknowledgeTsuzukiMihonProjection(chapterId, mihonId, row.generation)
                    true
                }
                if (applied) processed++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Preserve the queue on a failed legacy write (or fault injection).
                // A new checkpoint resets the backoff; after six failures, pause
                // this orphaned mapping until a new observation explicitly retries.
                attempted?.let { row ->
                    val nextAttempt = row.attemptCount + 1
                    val delay = (1L shl nextAttempt.toInt().coerceIn(1, 10)) * 1_000L
                    val retryAt = if (nextAttempt >= 6) Long.MAX_VALUE else nowMillis + delay
                    runCatching {
                        queue.retryTsuzukiMihonProjection(
                            nextRetryAt = retryAt,
                            canonicalChapterId = chapterId,
                            mihonChapterId = mihonId,
                            generation = row.generation,
                        )
                    }
                }
            }
        }
        return processed
    }

    private suspend fun loadProjection(chapterId: String, mihonId: Long): ProjectionRow? =
        database.tsuzuki_mihon_projection_queueQueries.getTsuzukiMihonProjection(chapterId, mihonId) {
            _, _, pendingProgress, progressRead, progressPage, pendingHistory, pendingDuration,
            historyReadAt, _, generation, attemptCount, nextRetryAt,
            ->
            ProjectionRow(
                pendingProgress = pendingProgress,
                read = progressRead,
                page = progressPage,
                pendingHistory = pendingHistory,
                pendingDuration = pendingDuration,
                historyReadAt = historyReadAt,
                generation = generation,
                attemptCount = attemptCount,
                nextRetryAt = nextRetryAt,
            )
        }.awaitAsOneOrNull()

    private data class ProjectionRow(
        val pendingProgress: Boolean,
        val read: Boolean?,
        val page: Long?,
        val pendingHistory: Boolean,
        val pendingDuration: Long,
        val historyReadAt: Long?,
        val generation: Long,
        val attemptCount: Long,
        val nextRetryAt: Long,
    )

    private suspend fun upsertProgressInternal(progress: CanonicalChapterProgress) {
        require(progress.lastPageRead >= 0L) { "lastPageRead must not be negative" }
        database.tsuzuki_chapter_progressQueries.upsertTsuzukiChapterProgress(
            canonicalChapterId = progress.canonicalChapterId,
            read = progress.read,
            lastPageRead = progress.lastPageRead,
            lastVariantId = progress.lastVariantId,
            updatedAt = progress.updatedAt,
        )
    }

    private suspend fun recordHistoryInternal(update: CanonicalChapterHistoryUpdate) {
        require(update.sessionReadDuration >= 0L) { "sessionReadDuration must not be negative" }
        database.tsuzuki_chapter_historyQueries.upsertTsuzukiChapterHistory(
            canonicalChapterId = update.canonicalChapterId,
            variantId = update.variantId,
            readAt = update.readAt,
            sessionReadDuration = update.sessionReadDuration,
        )
    }

    private fun mapProgress(
        canonicalChapterId: String,
        read: Boolean,
        lastPageRead: Long,
        lastVariantId: String?,
        updatedAt: Long,
    ) = CanonicalChapterProgress(
        canonicalChapterId = canonicalChapterId,
        read = read,
        lastPageRead = lastPageRead,
        lastVariantId = lastVariantId,
        updatedAt = updatedAt,
    )

    private fun mapHistory(
        canonicalChapterId: String,
        lastVariantId: String?,
        lastReadAt: Long?,
        timeRead: Long,
    ) = CanonicalChapterHistory(
        canonicalChapterId = canonicalChapterId,
        lastVariantId = lastVariantId,
        lastReadAt = lastReadAt,
        totalReadDuration = timeRead,
    )
}
