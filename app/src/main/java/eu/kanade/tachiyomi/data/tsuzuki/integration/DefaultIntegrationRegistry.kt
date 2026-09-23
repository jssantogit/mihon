package eu.kanade.tachiyomi.data.tsuzuki.integration

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider
import tachiyomi.domain.tsuzuki.integration.model.IntegrationSettings
import tachiyomi.domain.tsuzuki.integration.repository.IntegrationSettingsRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultIntegrationRegistry(
    settingsRepository: IntegrationSettingsRepository,
    private val searchProviders: Set<SearchProvider> = emptySet(),
    private val discoveryProviders: Set<DiscoveryProvider> = emptySet(),
    private val metadataProviders: Set<MetadataProvider> = emptySet(),
    private val chapterEvidenceProviders: Set<ChapterEvidenceProvider> = emptySet(),
    private val ratingsProviders: Set<RatingsProvider> = emptySet(),
    private val trackingProviders: Set<TrackingProvider> = emptySet(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : IntegrationRegistry {

    private val settings = MutableStateFlow<List<IntegrationSettings>>(emptyList())
    private val ready = CompletableDeferred<Unit>()

    init {
        settingsRepository.observeAll()
            .onEach {
                settings.value = it
                if (!ready.isCompleted) {
                    ready.complete(Unit)
                }
            }
            .launchIn(scope)
    }

    override suspend fun awaitReady() {
        ready.await()
    }

    override fun observeChanges(): Flow<Unit> = settings
        .drop(1)
        .map { Unit }

    override fun searchProviders(): List<SearchProvider> {
        val enabledIds = enabledIntegrationIds()
        return searchProviders.filter { it.integrationId.value in enabledIds }
    }

    override fun discoveryProviders(): List<DiscoveryProvider> {
        val enabledIds = enabledIntegrationIds()
        return discoveryProviders.filter { it.integrationId.value in enabledIds }
    }

    override fun metadataProviders(): List<MetadataProvider> {
        val enabledIds = enabledIntegrationIds()
        return metadataProviders.filter { it.integrationId.value in enabledIds }
    }

    override fun chapterEvidenceProviders(): List<ChapterEvidenceProvider> {
        val enabledIds = enabledIntegrationIds()
        return chapterEvidenceProviders.filter { it.producerId in enabledIds }
    }

    override fun ratingsProviders(): List<RatingsProvider> {
        val enabledIds = enabledIntegrationIds()
        return ratingsProviders.filter { it.integrationId.value in enabledIds }
    }

    override fun trackingProviders(): List<TrackingProvider> {
        val enabledIds = enabledIntegrationIds()
        return trackingProviders.filter { it.integrationId.value in enabledIds }
    }

    private fun enabledIntegrationIds(): Set<String> = settings.value
        .groupBy { it.integrationId.value }
        .mapNotNull { (integrationId, values) ->
            values.maxByOrNull(IntegrationSettings::updatedAt)
                ?.takeIf(IntegrationSettings::enabled)
                ?.let { integrationId }
        }
        .toSet()
}
