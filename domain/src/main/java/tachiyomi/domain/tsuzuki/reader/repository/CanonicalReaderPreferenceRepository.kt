package tachiyomi.domain.tsuzuki.reader.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreference

interface CanonicalReaderPreferenceRepository {
    suspend fun get(canonicalTitleId: String): CanonicalReaderPreference?
    fun observe(canonicalTitleId: String): Flow<CanonicalReaderPreference?>
    suspend fun upsert(preference: CanonicalReaderPreference)
}
