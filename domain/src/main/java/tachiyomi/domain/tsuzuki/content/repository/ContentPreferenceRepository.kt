package tachiyomi.domain.tsuzuki.content.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.content.ContentPreference

interface ContentPreferenceRepository {
    suspend fun get(canonicalTitleId: String): ContentPreference?

    suspend fun getAll(): List<ContentPreference> = emptyList()
    fun observe(canonicalTitleId: String): Flow<ContentPreference?>
    suspend fun upsert(preference: ContentPreference)
    suspend fun delete(canonicalTitleId: String)
}
