package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistory
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistoryUpdate
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository

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

    override suspend fun upsertProgress(progress: CanonicalChapterProgress) {
        upsertProgressInternal(progress)
    }

    override suspend fun getHistory(canonicalChapterId: String): CanonicalChapterHistory? {
        return database.tsuzuki_chapter_historyQueries
            .getTsuzukiChapterHistory(canonicalChapterId, ::mapHistory)
            .awaitAsOneOrNull()
    }

    override suspend fun recordHistory(update: CanonicalChapterHistoryUpdate) {
        recordHistoryInternal(update)
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
