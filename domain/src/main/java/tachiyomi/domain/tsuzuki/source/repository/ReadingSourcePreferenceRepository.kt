package tachiyomi.domain.tsuzuki.source.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.source.model.ReadingSourcePreference

interface ReadingSourcePreferenceRepository {
    suspend fun getForLanguage(language: String): List<ReadingSourcePreference>
    fun observeForLanguage(language: String): Flow<List<ReadingSourcePreference>>
    suspend fun getConfiguredLanguages(): List<String>
    suspend fun replaceForLanguage(language: String, orderedSourceIds: List<Long>)
}
