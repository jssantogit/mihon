package tachiyomi.domain.tsuzuki.catalog.service

import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery

interface CatalogProvider {
    val providerId: String
    val displayName: String

    suspend fun search(query: CatalogQuery): Result<CatalogPage>
    suspend fun getTrending(offset: Int = 0, limit: Int = 20): Result<CatalogPage>
    suspend fun getPopular(offset: Int = 0, limit: Int = 20): Result<CatalogPage>
    suspend fun getDetails(providerId: String): Result<CatalogItem>
}
