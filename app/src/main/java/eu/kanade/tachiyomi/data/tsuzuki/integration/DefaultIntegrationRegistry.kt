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
    private val integrationManifests: Set<IntegrationManifest> = DefaultIntegrationManifests.all.toSet(),
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

    override fun manifests(): List<IntegrationManifest> = integrationManifests.sortedBy { it.displayName }

    override fun searchProviders(): List<SearchProvider> = allowedProviders(\n        providers = searchProviders,\n        capability = IntegrationCapability.SEARCH,\n    )

    override fun discoveryProviders(): List<DiscoveryProvider> = allowedProviders(\n        providers = discoveryProviders,\n        capability = IntegrationCapability.DISCOVERY,\n    )

    override fun metadataProviders(): List<MetadataProvider> = allowedProviders(\n        providers = metadataProviders,\n        capability = IntegrationCapability.METADATA_BASIC,\n    )

    override fun chapterEvidenceProviders(): List<ChapterEvidenceProvider> {
        val enabledIds = enabledIntegrationIds("chapter_evidence")
        return chapterEvidenceProviders.filter { it.producerId in enabledIds }
    }

    override fun ratingsProviders(): List<RatingsProvider> = allowedProviders(\n        providers = ratingsProviders,\n        capability = IntegrationCapability.RATINGS,\n    )

    override fun trackingProviders(): List<TrackingProvider> {\n        val enabledIds = enabledIntegrationIds(IntegrationCapability.TRACKING)\n        return trackingProviders.filter { it.integrationId.value in enabledIds }\n    }\n\n    private fun <T> allowedProviders(\n        providers: Set<T>,\n        capability: IntegrationCapability,\n    ): List<T> where T : Any {\n        val enabledIds = enabledIntegrationIds(capability)\n        return providers.filter { provider ->\n            val id = when (provider) {\n                is SearchProvider -> provider.integrationId\n                is DiscoveryProvider -> provider.integrationId\n                is MetadataProvider -> provider.integrationId\n                is RatingsProvider -> provider.integrationId\n                else -> return@filter false\n            }\n            if (id.value !in enabledIds) return@filter false\n            val manifest = integrationManifests.firstOrNull { it.integrationId == id }\n            manifest == null || manifest.allowsGlobalResolution(capability)\n        }\n    }

    private fun enabledIntegrationIds(capability: IntegrationCapability): Set<String> = enabledIntegrationIds(capability.configKey)\n\n    private fun enabledIntegrationIds(capability: String): Set<String> = settings.value
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
