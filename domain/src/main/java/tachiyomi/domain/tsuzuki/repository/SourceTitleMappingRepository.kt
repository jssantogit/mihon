package tachiyomi.domain.tsuzuki.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping

interface SourceTitleMappingRepository {
    suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<SourceTitleMapping>
    fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<SourceTitleMapping>>
    suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping?
    suspend fun upsert(mapping: SourceTitleMapping)
}
