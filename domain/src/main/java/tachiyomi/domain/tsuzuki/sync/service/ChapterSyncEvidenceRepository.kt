package tachiyomi.domain.tsuzuki.sync.service

fun interface ChapterSyncEvidenceRepository {
    suspend fun getStableEvidenceKey(canonicalChapterId: String): String?
}
