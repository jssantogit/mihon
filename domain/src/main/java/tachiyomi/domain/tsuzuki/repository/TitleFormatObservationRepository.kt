package tachiyomi.domain.tsuzuki.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.library.model.TitleFormatObservation

interface TitleFormatObservationRepository {
    fun observeAll(): Flow<List<TitleFormatObservation>>

    suspend fun upsert(observation: TitleFormatObservation)
}
