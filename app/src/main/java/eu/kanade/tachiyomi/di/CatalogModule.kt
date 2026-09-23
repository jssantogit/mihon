package eu.kanade.tachiyomi.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import tachiyomi.domain.tsuzuki.catalog.interactor.GetDiscoverFeed
import tachiyomi.domain.tsuzuki.catalog.interactor.SearchCatalog
import tachiyomi.domain.tsuzuki.catalog.service.CatalogProvider
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitleFromCatalog

@ContributesTo(AppScope::class)
interface CatalogModule {
    val catalogProvider: CatalogProvider
    val searchCatalog: SearchCatalog
    val getDiscoverFeed: GetDiscoverFeed
    val materializeCanonicalTitleFromCatalog: MaterializeCanonicalTitleFromCatalog
}
