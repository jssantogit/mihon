package tachiyomi.domain.tsuzuki.integration

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import tachiyomi.domain.tsuzuki.integration.model.IntegrationManifest

interface IntegrationRegistry {
    suspend fun awaitReady() = Unit

    fun observeChanges(): Flow<Unit> = emptyFlow()

    fun manifests(): List<IntegrationManifest> = emptyList()

    fun configurationFingerprint(): String = ""

    fun isGlobalCapabilityActive(
        integrationId: IntegrationId,
        capability: IntegrationCapability,
    ): Boolean = false

    fun searchProviders(): List<SearchProvider>

    fun discoveryProviders(): List<DiscoveryProvider>

    fun metadataProviders(): List<MetadataProvider>

    fun metadataProviders(capability: IntegrationCapability): List<MetadataProvider> = metadataProviders()

    fun chapterEvidenceProviders(): List<ChapterEvidenceProvider>

    fun ratingsProviders(): List<RatingsProvider>

    fun trackingProviders(): List<TrackingProvider>

    fun userListProviders(): List<UserListProvider> = emptyList()
}
