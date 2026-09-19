package tachiyomi.domain.tsuzuki.chapter.repository

import tachiyomi.domain.tsuzuki.chapter.model.ChapterOverride

interface ChapterOverrideRepository {
    suspend fun getById(id: String): ChapterOverride?
    suspend fun getAll(includeDeleted: Boolean = false): List<ChapterOverride>
    suspend fun upsert(override: ChapterOverride)
}
