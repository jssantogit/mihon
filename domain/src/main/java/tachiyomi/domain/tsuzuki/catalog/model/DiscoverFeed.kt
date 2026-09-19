package tachiyomi.domain.tsuzuki.catalog.model

data class DiscoverFeed(
    val trending: Result<CatalogPage>,
    val popular: Result<CatalogPage>,
) {
    val isDegraded: Boolean get() = trending.isFailure || popular.isFailure
    val isCompleteFailure: Boolean get() = trending.isFailure && popular.isFailure
}
