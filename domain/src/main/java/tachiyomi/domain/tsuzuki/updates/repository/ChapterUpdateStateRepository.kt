package tachiyomi.domain.tsuzuki.updates.repository

interface ChapterUpdateStateRepository {
    suspend fun acknowledge(
        canonicalChapterId: String,
        acknowledgedAt: Long,
    )
}
