package tachiyomi.domain.tsuzuki.updates.repository

data class ChapterUpdateState(
    val canonicalChapterId: String,
    val canonicalTitleId: String,
    val firstSeenAt: Long,
    val acknowledgedAt: Long?,
)

interface ChapterUpdateStateRepository {
    suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<ChapterUpdateState>

    suspend fun getUnacknowledgedByCanonicalTitleId(canonicalTitleId: String): List<ChapterUpdateState>

    suspend fun upsert(state: ChapterUpdateState)

    suspend fun acknowledge(canonicalChapterId: String, acknowledgedAt: Long)

    suspend fun delete(canonicalChapterId: String)
}
