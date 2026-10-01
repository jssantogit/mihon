package tachiyomi.data.tsuzuki.chapter

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
import tachiyomi.domain.tsuzuki.chapter.refresh.ChapterRefreshSnapshot
import tachiyomi.domain.tsuzuki.chapter.refresh.ChapterRefreshSnapshotRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ChapterRefreshSnapshotRepositoryImpl(
    private val database: Database,
) : ChapterRefreshSnapshotRepository {

    override suspend fun get(canonicalTitleId: String, scopeKey: String): ChapterRefreshSnapshot? =
        database.tsuzuki_chapter_refresh_snapshotQueries
            .getTsuzukiChapterRefreshSnapshot(canonicalTitleId, scopeKey, ::mapSnapshot)
            .awaitAsOneOrNull()

    override suspend fun upsertIfNewer(snapshot: ChapterRefreshSnapshot): ChapterRefreshSnapshot =
        database.transactionWithResult {
            val existing = get(snapshot.canonicalTitleId, snapshot.scopeKey)
            if (existing != null && !snapshot.canReplace(existing)) {
                return@transactionWithResult existing
            }
            database.tsuzuki_chapter_refresh_snapshotQueries.upsertTsuzukiChapterRefreshSnapshot(
                canonicalTitleId = snapshot.canonicalTitleId,
                scopeKey = snapshot.scopeKey,
                fingerprint = snapshot.fingerprint,
                configurationFingerprint = snapshot.configurationFingerprint,
                observedAt = snapshot.observedAt,
                refreshedAt = snapshot.refreshedAt,
                itemCount = snapshot.itemCount.toLong(),
            )
            snapshot
        }

    override suspend fun invalidateTitle(canonicalTitleId: String) {
        database.tsuzuki_chapter_refresh_snapshotQueries
            .deleteTsuzukiChapterRefreshSnapshotsByTitle(canonicalTitleId)
    }

    private fun ChapterRefreshSnapshot.canReplace(existing: ChapterRefreshSnapshot): Boolean =
        observedAt > existing.observedAt ||
            (observedAt == existing.observedAt && fingerprint == existing.fingerprint)

    private fun mapSnapshot(
        canonicalTitleId: String,
        scopeKey: String,
        fingerprint: String?,
        configurationFingerprint: String,
        observedAt: Long,
        refreshedAt: Long,
        itemCount: Long,
    ) = ChapterRefreshSnapshot(
        canonicalTitleId = canonicalTitleId,
        scopeKey = scopeKey,
        fingerprint = fingerprint,
        configurationFingerprint = configurationFingerprint,
        observedAt = observedAt,
        refreshedAt = refreshedAt,
        itemCount = itemCount.toInt(),
    )
}
