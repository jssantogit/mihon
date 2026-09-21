package tachiyomi.domain.tsuzuki.home.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.home.model.ContinueReadingVisibility

interface ContinueReadingVisibilityRepository {
    suspend fun get(canonicalTitleId: String): ContinueReadingVisibility?

    suspend fun getAll(): List<ContinueReadingVisibility>

    fun observeAll(): Flow<List<ContinueReadingVisibility>>

    suspend fun hide(
        canonicalTitleId: String,
        hiddenAt: Long,
    )

    suspend fun clear(canonicalTitleId: String)
}
