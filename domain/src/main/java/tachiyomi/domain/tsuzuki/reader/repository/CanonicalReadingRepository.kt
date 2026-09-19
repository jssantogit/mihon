package tachiyomi.domain.tsuzuki.reader.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistory
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistoryUpdate
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress

interface CanonicalReadingRepository {

    suspend fun getProgress(canonicalChapterId: String): CanonicalChapterProgress?

    fun observeProgress(canonicalChapterId: String): Flow<CanonicalChapterProgress?>

    suspend fun getProgressByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapterProgress>

    suspend fun upsertProgress(progress: CanonicalChapterProgress)

    suspend fun getHistory(canonicalChapterId: String): CanonicalChapterHistory?

    suspend fun recordHistory(update: CanonicalChapterHistoryUpdate)

    /** Applies one Reader checkpoint atomically. */
    suspend fun recordCheckpoint(
        progress: CanonicalChapterProgress,
        history: CanonicalChapterHistoryUpdate? = null,
    )
}
