package tachiyomi.domain.tsuzuki.integration

interface IntegrationRegistry {
    fun searchProviders(): List<SearchProvider>

    fun discoveryProviders(): List<DiscoveryProvider>

    fun metadataProviders(): List<MetadataProvider>

    fun chapterEvidenceProviders(): List<ChapterEvidenceProvider>

    fun ratingsProviders(): List<RatingsProvider>

    fun trackingProviders(): List<TrackingProvider>
}
