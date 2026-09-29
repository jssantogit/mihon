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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import tachiyomi.domain.tsuzuki.integration.model.IntegrationManifest
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

    private val integrationManifests: Set<IntegrationManifest> = DefaultIntegrationManifests.all.toSet()
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

    override fun manifests(): List<IntegrationManifest> = integrationManifests.sortedBy { it.displayName }

    override fun searchProviders(): List<SearchProvider> = allowedProviders(
        providers = searchProviders,
        capability = IntegrationCapability.SEARCH,
    )

    override fun discoveryProviders(): List<DiscoveryProvider> = allowedProviders(
        providers = discoveryProviders,
        capability = IntegrationCapability.DISCOVERY,
    )

    override fun metadataProviders(): List<MetadataProvider> = allowedProviders(
        providers = metadataProviders,
        capability = IntegrationCapability.METADATA_BASIC,
    )

    override fun chapterEvidenceProviders(): List<ChapterEvidenceProvider> {
        val enabledIds = enabledIntegrationIds("chapter_evidence")
        return chapterEvidenceProviders.filter { it.producerId in enabledIds }
    }

    override fun ratingsProviders(): List<RatingsProvider> = allowedProviders(
        providers = ratingsProviders,
        capability = IntegrationCapability.RATINGS,
    )

    override fun trackingProviders(): List<TrackingProvider> {
        val enabledIds = enabledIntegrationIds(IntegrationCapability.TRACKING)
        return trackingProviders.filter { it.integrationId.value in enabledIds }
    }

    private fun <T> allowedProviders(
        providers: Set<T>,
        capability: IntegrationCapability,
    ): List<T> where T : Any {
        val enabledIds = enabledIntegrationIds(capability)
        return providers.filter { provider ->
            val id = when (provider) {
                is SearchProvider -> provider.integrationId
                is DiscoveryProvider -> provider.integrationId
                is MetadataProvider -> provider.integrationId
                is RatingsProvider -> provider.integrationId
                else -> return@filter false
            }
            if (id.value !in enabledIds) return@filter false
            val manifest = integrationManifests.firstOrNull { it.integrationId == id }
            manifest == null || manifest.allowsGlobalResolution(capability)
        }
    }

    private fun enabledIntegrationIds(capability: IntegrationCapability): Set<String> = enabledIntegrationIds(
        capability.configKey,
    )

    private fun enabledIntegrationIds(capability: String): Set<String> = settings.value
        .groupBy { it.integrationId.value }
        .mapNotNull { (integrationId, values) ->
            values.maxByOrNull(IntegrationSettings::updatedAt)
                ?.takeIf(IntegrationSettings::enabled)
                ?.takeIf { it.capabilityEnabled(capability) }
                ?.let { integrationId }
        }
        .toSet()

    private fun IntegrationSettings.capabilityEnabled(capability: String): Boolean {
        val config = runCatching {
            Json.parseToJsonElement(configJson) as? JsonObject
        }.getOrNull()
        return config
            ?.get(capability)
            ?.jsonPrimitive
            ?.booleanOrNull
            ?: true
    }
}
