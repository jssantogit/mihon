package tachiyomi.data.tsuzuki.updates

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
import tachiyomi.domain.tsuzuki.updates.repository.ChapterUpdateState
import tachiyomi.domain.tsuzuki.updates.repository.ChapterUpdateStateRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ChapterUpdateStateRepositoryImpl(
    private val database: Database,
) : ChapterUpdateStateRepository {

    override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<ChapterUpdateState> {
        return database.tsuzuki_chapter_update_stateQueries
            .getTsuzukiChapterUpdateStateByTitle(canonicalTitleId, ::mapState)
            .awaitAsList()
    }

    override suspend fun getUnacknowledgedByCanonicalTitleId(
        canonicalTitleId: String,
    ): List<ChapterUpdateState> {
        return database.tsuzuki_chapter_update_stateQueries
            .getTsuzukiUnacknowledgedChapterUpdatesByTitle(canonicalTitleId, ::mapState)
            .awaitAsList()
    }

    override suspend fun upsert(state: ChapterUpdateState) {
        database.tsuzuki_chapter_update_stateQueries.upsertTsuzukiChapterUpdateState(
            canonicalChapterId = state.canonicalChapterId,
            canonicalTitleId = state.canonicalTitleId,
            firstSeenAt = state.firstSeenAt,
            acknowledgedAt = state.acknowledgedAt,
        )
    }

    override suspend fun acknowledge(canonicalChapterId: String, acknowledgedAt: Long) {
        database.tsuzuki_chapter_update_stateQueries.acknowledgeTsuzukiChapterUpdate(
            canonicalChapterId = canonicalChapterId,
            acknowledgedAt = acknowledgedAt,
        )
    }

    override suspend fun delete(canonicalChapterId: String) {
        database.tsuzuki_chapter_update_stateQueries.deleteTsuzukiChapterUpdateState(canonicalChapterId)
    }

    private fun mapState(
        canonicalChapterId: String,
        canonicalTitleId: String,
        firstSeenAt: Long,
        acknowledgedAt: Long?,
    ) = ChapterUpdateState(
        canonicalChapterId = canonicalChapterId,
        canonicalTitleId = canonicalTitleId,
        firstSeenAt = firstSeenAt,
        acknowledgedAt = acknowledgedAt,
    )
}
