package tachiyomi.domain.tsuzuki.reader.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreference

interface CanonicalReaderPreferenceRepository {
    suspend fun get(canonicalTitleId: String): CanonicalReaderPreference?
    suspend fun getAll(): List<CanonicalReaderPreference> = emptyList()
    fun observe(canonicalTitleId: String): Flow<CanonicalReaderPreference?>
    suspend fun upsert(preference: CanonicalReaderPreference)
    suspend fun delete(canonicalTitleId: String) = Unit
}
