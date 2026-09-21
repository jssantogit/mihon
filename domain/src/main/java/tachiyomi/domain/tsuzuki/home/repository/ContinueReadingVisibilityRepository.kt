package tachiyomi.domain.tsuzuki.home.repository

import tachiyomi.domain.tsuzuki.home.model.ContinueReadingVisibility

interface ContinueReadingVisibilityRepository {
    suspend fun get(canonicalTitleId: String): ContinueReadingVisibility?

    suspend fun getAll(): List<ContinueReadingVisibility>

    suspend fun hide(
        canonicalTitleId: String,
        hiddenAt: Long,
    )

    suspend fun clear(canonicalTitleId: String)
}
