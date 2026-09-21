package tachiyomi.data.tsuzuki.integration

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.model.IntegrationSettings
import tachiyomi.domain.tsuzuki.integration.repository.IntegrationSettingsRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class IntegrationSettingsRepositoryImpl(
    private val database: Database,
) : IntegrationSettingsRepository {

    override suspend fun get(id: IntegrationId): IntegrationSettings? {
        return database.tsuzuki_integration_settingsQueries
            .getTsuzukiIntegrationSetting(id.value, ::mapSettings)
            .awaitAsOneOrNull()
    }

    override fun observeAll(): Flow<List<IntegrationSettings>> {
        return database.tsuzuki_integration_settingsQueries
            .getAllTsuzukiIntegrationSettings(::mapSettings)
            .subscribeToList()
    }

    override suspend fun upsert(settings: IntegrationSettings) {
        database.tsuzuki_integration_settingsQueries.upsertTsuzukiIntegrationSetting(
            integrationId = settings.integrationId.value,
            enabled = settings.enabled,
            configJson = settings.configJson,
            updatedAt = settings.updatedAt,
        )
    }

    private fun mapSettings(
        integrationId: String,
        enabled: Boolean,
        configJson: String,
        updatedAt: Long,
    ) = IntegrationSettings(
        integrationId = IntegrationId(integrationId),
        enabled = enabled,
        configJson = configJson,
        updatedAt = updatedAt,
    )
}
