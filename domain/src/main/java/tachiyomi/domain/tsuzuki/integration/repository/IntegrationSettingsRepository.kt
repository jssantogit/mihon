package tachiyomi.domain.tsuzuki.integration.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.model.IntegrationSettings

interface IntegrationSettingsRepository {
    suspend fun get(id: IntegrationId): IntegrationSettings?

    fun observeAll(): Flow<List<IntegrationSettings>>

    suspend fun upsert(settings: IntegrationSettings)
}
