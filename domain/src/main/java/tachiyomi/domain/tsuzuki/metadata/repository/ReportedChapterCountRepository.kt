package tachiyomi.domain.tsuzuki.metadata.repository

import tachiyomi.domain.tsuzuki.metadata.ReportedChapterCount

interface ReportedChapterCountRepository {
    suspend fun getByTitle(canonicalTitleId: String): List<ReportedChapterCount>
    suspend fun upsert(value: ReportedChapterCount)
}
