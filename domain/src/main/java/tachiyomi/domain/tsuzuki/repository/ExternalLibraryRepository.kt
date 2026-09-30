package tachiyomi.domain.tsuzuki.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.library.model.ExternalLibraryMembership

interface ExternalLibraryRepository {
    fun observeAll(): Flow<List<ExternalLibraryMembership>>

    suspend fun replaceProvider(
        provider: String,
        memberships: List<ExternalLibraryMembership>,
    )

    suspend fun clearProvider(provider: String)

    suspend fun getProviderIds(): Set<String>
}
