package tachiyomi.domain.tsuzuki.reader.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistory
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistoryUpdate
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress

interface CanonicalReadingRepository {

    suspend fun getProgress(canonicalChapterId: String): CanonicalChapterProgress?

    fun observeProgress(canonicalChapterId: String): Flow<CanonicalChapterProgress?>

    suspend fun getProgressByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapterProgress>

    /**
     * Observes canonical progress for a title so local Home state reacts to Reader checkpoints.
     * Implementations backed by a reactive store should override this fallback.
     */
    fun observeProgressByCanonicalTitleId(
        canonicalTitleId: String,
    ): Flow<List<CanonicalChapterProgress>> = kotlinx.coroutines.flow.flow {
        emit(getProgressByCanonicalTitleId(canonicalTitleId))
    }

    suspend fun upsertProgress(progress: CanonicalChapterProgress)

    /**
     * The production SQL repository atomically persists canonical progress and
     * the pending Mihon projection. The default exists only for non-SQL fakes.
     */
    suspend fun recordProgressWithProjection(
        progress: CanonicalChapterProgress,
        mihonChapterId: Long,
    ) = upsertProgress(progress)

    /**
     * History duration must be enqueued in the SAME SQLite transaction as its
     * canonical increment, never dispatched as an unacknowledged additive call.
     */
    suspend fun recordHistoryWithProjection(
        update: CanonicalChapterHistoryUpdate,
        mihonChapterId: Long,
    ) = recordHistory(update)

    /** Bounded, idempotent replay; the production repository overrides this. */
    suspend fun drainPendingProjections(limit: Int = 4): Int = 0

    suspend fun getHistory(canonicalChapterId: String): CanonicalChapterHistory?

    suspend fun recordHistory(update: CanonicalChapterHistoryUpdate)

    /** Applies one Reader checkpoint atomically. */
    suspend fun recordCheckpoint(
        progress: CanonicalChapterProgress,
        history: CanonicalChapterHistoryUpdate? = null,
    )
}
