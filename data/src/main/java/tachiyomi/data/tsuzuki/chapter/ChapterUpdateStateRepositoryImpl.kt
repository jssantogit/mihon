package tachiyomi.data.tsuzuki.chapter

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.tsuzuki.chapter.update.model.CanonicalChapterUpdateState
import tachiyomi.domain.tsuzuki.chapter.update.repository.ChapterUpdateStateRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ChapterUpdateStateRepositoryImpl(
    private val database: Database,
) : ChapterUpdateStateRepository {

    override suspend fun getAll(): List<CanonicalChapterUpdateState> {
        return database.tsuzuki_chapter_update_stateQueries
            .getAllTsuzukiChapterUpdateState()
            .awaitAsList()
            .map { row ->
                CanonicalChapterUpdateState(
                    canonicalChapterId = row.canonical_chapter_id,
                    canonicalTitleId = row.canonical_title_id,
                    firstSeenAt = row.first_seen_at,
                    acknowledgedAt = row.acknowledged_at,
                )
            }
    }

    override suspend fun getByTitle(
        canonicalTitleId: String,
    ): List<CanonicalChapterUpdateState> {
        return database.tsuzuki_chapter_update_stateQueries
            .getTsuzukiChapterUpdateStateByTitle(canonicalTitleId)
            .awaitAsList()
            .map { row ->
                CanonicalChapterUpdateState(
                    canonicalChapterId = row.canonical_chapter_id,
                    canonicalTitleId = row.canonical_title_id,
                    firstSeenAt = row.first_seen_at,
                    acknowledgedAt = row.acknowledged_at,
                )
            }
    }

    override fun observeByTitle(
        canonicalTitleId: String,
    ): Flow<List<CanonicalChapterUpdateState>> {
        return database.tsuzuki_chapter_update_stateQueries
            .getTsuzukiChapterUpdateStateByTitle(canonicalTitleId)
            .subscribeToList()
            .let { flow ->
                kotlinx.coroutines.flow.map(flow) { rows ->
                    rows.map { row ->
                        CanonicalChapterUpdateState(
                            canonicalChapterId = row.canonical_chapter_id,
                            canonicalTitleId = row.canonical_title_id,
                            firstSeenAt = row.first_seen_at,
                            acknowledgedAt = row.acknowledged_at,
                        )
                    }
                }
            }
    }

    override suspend fun upsert(state: CanonicalChapterUpdateState) {
        database.tsuzuki_chapter_update_stateQueries
            .upsertTsuzukiChapterUpdateState(
                canonicalChapterId = state.canonicalChapterId,
                canonicalTitleId = state.canonicalTitleId,
                firstSeenAt = state.firstSeenAt,
                acknowledgedAt = state.acknowledgedAt,
            )
    }

    override suspend fun acknowledge(
        canonicalChapterId: String,
        acknowledgedAt: Long,
    ) {
        database.tsuzuki_chapter_update_stateQueries
            .acknowledgeTsuzukiChapterUpdate(
                acknowledgedAt = acknowledgedAt,
                canonicalChapterId = canonicalChapterId,
            )
    }

    override suspend fun delete(canonicalChapterId: String) {
        database.tsuzuki_chapter_update_stateQueries
            .deleteTsuzukiChapterUpdateState(canonicalChapterId)
    }
}
