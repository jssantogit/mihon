package tachiyomi.domain.tsuzuki.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.library.model.LibraryTitle
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry

interface CanonicalLibraryRepository {
    suspend fun get(canonicalTitleId: String): CanonicalLibraryEntry?
    fun getAllAsFlow(): Flow<List<CanonicalLibraryEntry>>
    fun getAllItemsAsFlow(): Flow<List<LibraryTitle>>
    suspend fun upsert(entry: CanonicalLibraryEntry)
    suspend fun remove(canonicalTitleId: String)
}
