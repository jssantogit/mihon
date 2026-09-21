package eu.kanade.tachiyomi.data.tsuzuki.integration

import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider
import tachiyomi.domain.tsuzuki.integration.model.IntegrationSettings

class DefaultIntegrationRegistry(
    private val settings: List<IntegrationSettings>,
    private val searchProviders: List<SearchProvider>,
    private val discoveryProviders: List<DiscoveryProvider>,
    private val metadataProviders: List<MetadataProvider>,
    private val chapterEvidenceProviders: List<ChapterEvidenceProvider>,
    private val ratingsProviders: List<RatingsProvider>,
    private val trackingProviders: List<TrackingProvider> = emptyList(),
) : IntegrationRegistry {

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

    private fun enabledIntegrationIds(): Set<String> = settings
        .groupBy { it.integrationId.value }
        .mapNotNull { (integrationId, values) ->
            values.maxByOrNull(IntegrationSettings::updatedAt)
                ?.takeIf(IntegrationSettings::enabled)
                ?.let { integrationId }
        }
        .toSet()
}
