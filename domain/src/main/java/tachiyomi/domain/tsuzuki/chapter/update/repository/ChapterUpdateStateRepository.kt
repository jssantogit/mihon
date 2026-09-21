package tachiyomi.domain.tsuzuki.chapter.update.repository

import tachiyomi.domain.tsuzuki.chapter.update.model.CanonicalChapterUpdateState

interface ChapterUpdateStateRepository {
    suspend fun getAll(): List<CanonicalChapterUpdateState>

    suspend fun getByTitle(canonicalTitleId: String): List<CanonicalChapterUpdateState>

    suspend fun upsert(state: CanonicalChapterUpdateState)

    suspend fun acknowledge(
        canonicalChapterId: String,
        acknowledgedAt: Long,
    )

    suspend fun delete(canonicalChapterId: String)
}
