package tachiyomi.domain.tsuzuki.chapter.refresh

interface ChapterRefreshSnapshotRepository {
    suspend fun get(canonicalTitleId: String, scopeKey: String): ChapterRefreshSnapshot?

    suspend fun upsertIfNewer(snapshot: ChapterRefreshSnapshot): ChapterRefreshSnapshot

    suspend fun invalidateTitle(canonicalTitleId: String) = Unit
}
