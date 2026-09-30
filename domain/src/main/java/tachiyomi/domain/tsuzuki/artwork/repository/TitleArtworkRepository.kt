package tachiyomi.domain.tsuzuki.artwork.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.artwork.model.TitleArtworkObservation

interface TitleArtworkRepository {
    fun observeAll(): Flow<List<TitleArtworkObservation>>

    suspend fun getByTitle(canonicalTitleId: String): List<TitleArtworkObservation>

    suspend fun upsert(observation: TitleArtworkObservation)
}
