package tachiyomi.domain.tsuzuki.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping

interface SourceTitleMappingRepository {
    suspend fun getAll(): List<SourceTitleMapping> {
        throw UnsupportedOperationException("Listing all source mappings is not supported")
    }

    fun getAllAsFlow(): Flow<List<SourceTitleMapping>>

    suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<SourceTitleMapping>
    fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<SourceTitleMapping>>
    suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping?
    suspend fun upsert(mapping: SourceTitleMapping)

    suspend fun remove(id: String) {
        throw UnsupportedOperationException("Removing source mappings by ID is not supported")
    }

    suspend fun setPreferredForTitle(canonicalTitleId: String, mappingId: String?, updatedAt: Long)
}
