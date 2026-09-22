package tachiyomi.data.tsuzuki.metadata

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
import tachiyomi.domain.tsuzuki.metadata.ReportedChapterCount
import tachiyomi.domain.tsuzuki.metadata.repository.ReportedChapterCountRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ReportedChapterCountRepositoryImpl(
    private val database: Database,
) : ReportedChapterCountRepository {

    override suspend fun getByTitle(canonicalTitleId: String): List<ReportedChapterCount> =
        database.tsuzuki_reported_chapter_countsQueries
            .getTsuzukiReportedChapterCountsByTitle(canonicalTitleId) { titleId, provider, chapterCount, updatedAt ->
                ReportedChapterCount(
                    canonicalTitleId = titleId,
                    provider = provider,
                    chapterCount = chapterCount?.toInt(),
                    updatedAt = updatedAt,
                )
            }
            .awaitAsList()

    override suspend fun upsert(value: ReportedChapterCount) {
        database.tsuzuki_reported_chapter_countsQueries.upsertTsuzukiReportedChapterCount(
            canonicalTitleId = value.canonicalTitleId,
            provider = value.provider,
            chapterCount = value.chapterCount?.toLong(),
            updatedAt = value.updatedAt,
        )
    }
}
