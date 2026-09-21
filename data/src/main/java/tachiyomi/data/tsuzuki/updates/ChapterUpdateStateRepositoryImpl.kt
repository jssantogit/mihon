package tachiyomi.data.tsuzuki.updates

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
import tachiyomi.domain.tsuzuki.updates.repository.ChapterUpdateStateRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ChapterUpdateStateRepositoryImpl(
    private val database: Database,
) : ChapterUpdateStateRepository {

    override suspend fun acknowledge(
        canonicalChapterId: String,
        acknowledgedAt: Long,
    ) {
        database.tsuzuki_chapter_update_stateQueries.acknowledgeTsuzukiChapterUpdate(
            acknowledgedAt = acknowledgedAt,
            canonicalChapterId = canonicalChapterId,
        )
    }
}
