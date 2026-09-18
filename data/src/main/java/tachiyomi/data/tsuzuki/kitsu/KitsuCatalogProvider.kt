package tachiyomi.data.tsuzuki.kitsu

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.tsuzuki.kitsu.client.KitsuClient
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMangaResource
import tachiyomi.domain.tsuzuki.catalog.model.CatalogError
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.model.CatalogScore
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.catalog.service.CatalogProvider

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class KitsuCatalogProvider(
    private val client: KitsuClient,
) : CatalogProvider {

    override val providerId: String = "kitsu"
    override val displayName: String = "Kitsu"

    override suspend fun search(query: CatalogQuery): Result<CatalogPage> {
        val sortParam = when (query.sort) {
            CatalogSort.POPULARITY_DESC -> "-userCount"
            CatalogSort.POPULARITY_ASC -> "userCount"
            CatalogSort.RATING_DESC -> "-averageRating"
            CatalogSort.RATING_ASC -> "averageRating"
            CatalogSort.UPDATED_DESC -> "-updatedAt"
            CatalogSort.RELEVANCE -> null
        }
        val statusParam = when (query.status) {
            CatalogItemStatus.ONGOING -> "current"
            CatalogItemStatus.COMPLETED -> "finished"
            CatalogItemStatus.CANCELLED -> "cancelled"
            CatalogItemStatus.ON_HIATUS -> "unapproved"
            CatalogItemStatus.UNKNOWN, null -> null
        }

        return client.searchManga(
            query = query.query,
            offset = query.offset,
            limit = query.limit,
            sort = sortParam,
            status = statusParam,
        ).mapCatalog { response ->
            val items = response.data.map(::mapResourceToItem)
            val hasNext =
                response.links?.next != null ||
                    (response.meta?.count != null && query.offset + items.size < response.meta.count)
            CatalogPage(
                items = items,
                hasNextPage = hasNext,
                totalCount = response.meta?.count,
            )
        }
    }

    override suspend fun getTrending(offset: Int, limit: Int): Result<CatalogPage> {
        return client.getTrendingManga(limit).mapCatalog { response ->
            val items = response.data.map(::mapResourceToItem)
            CatalogPage(
                items = items,
                hasNextPage = false,
                totalCount = items.size,
            )
        }
    }

    override suspend fun getPopular(offset: Int, limit: Int): Result<CatalogPage> {
        return client.getPopularManga(offset, limit).mapCatalog { response ->
            val items = response.data.map(::mapResourceToItem)
            val hasNext =
                response.links?.next != null ||
                    (response.meta?.count != null && offset + items.size < response.meta.count)
            CatalogPage(
                items = items,
                hasNextPage = hasNext,
                totalCount = response.meta?.count,
            )
        }
    }

    override suspend fun getDetails(providerId: String): Result<CatalogItem> {
        return client.getMangaById(providerId).mapCatalog { response ->
            mapResourceToItem(response.data)
        }
    }

    internal fun mapResourceToItem(resource: KitsuMangaResource): CatalogItem {
        val attr = resource.attributes
        val bestTitle = attr.canonicalTitle?.takeIf { it.isNotBlank() }
            ?: attr.titles?.en?.takeIf { it.isNotBlank() }
            ?: attr.titles?.enJp?.takeIf { it.isNotBlank() }
            ?: attr.titles?.jaJp?.takeIf { it.isNotBlank() }
            ?: "Unknown"

        val titlesMap = buildMap {
            attr.canonicalTitle?.takeIf { it.isNotBlank() }?.let { put("canonical", it) }
            attr.titles?.en?.takeIf { it.isNotBlank() }?.let { put("en", it) }
            attr.titles?.enJp?.takeIf { it.isNotBlank() }?.let { put("en_jp", it) }
            attr.titles?.jaJp?.takeIf { it.isNotBlank() }?.let { put("ja_jp", it) }
            attr.titles?.enUs?.takeIf { it.isNotBlank() }?.let { put("en_us", it) }
        }

        val mappedStatus = when (attr.status?.lowercase()) {
            "current" -> CatalogItemStatus.ONGOING
            "finished" -> CatalogItemStatus.COMPLETED
            "unreleased", "tba" -> CatalogItemStatus.UNKNOWN
            else -> CatalogItemStatus.UNKNOWN
        }

        val mappedFormat = when (attr.subtype?.lowercase()) {
            "manga" -> CatalogItemFormat.MANGA
            "novel" -> CatalogItemFormat.NOVEL
            "oneshot" -> CatalogItemFormat.ONE_SHOT
            "manhwa" -> CatalogItemFormat.MANHWA
            "manhua" -> CatalogItemFormat.MANHUA
            "doujin" -> CatalogItemFormat.DOUJIN
            else -> CatalogItemFormat.UNKNOWN
        }

        val score = attr.averageRating?.takeIf { it.isNotBlank() }?.toDoubleOrNull()?.let { rating ->
            CatalogScore(
                provider = providerId,
                value = rating,
                maxValue = 100.0,
                voteCount = attr.userCount,
            )
        }

        val cover = attr.posterImage?.let {
            it.original ?: it.large ?: it.medium ?: it.small
        }

        val banner = attr.coverImage?.let {
            it.original ?: it.large ?: it.small
        }

        return CatalogItem(
            provider = providerId,
            providerId = resource.id,
            title = bestTitle,
            titles = titlesMap,
            synopsis = attr.synopsis ?: attr.description,
            coverUrl = cover,
            bannerUrl = banner,
            status = mappedStatus,
            format = mappedFormat,
            score = score,
            genres = emptyList(),
            tags = emptyList(),
            startDate = attr.startDate,
            endDate = attr.endDate,
            chapterCount = attr.chapterCount,
            volumeCount = attr.volumeCount,
        )
    }

    private inline fun <T, R> Result<T>.mapCatalog(transform: (T) -> R): Result<R> {
        return fold(
            onSuccess = { value ->
                try {
                    Result.success(transform(value))
                } catch (e: Exception) {
                    if (e is CatalogError) {
                        Result.failure(e)
                    } else {
                        Result.failure(CatalogError.SerializationError(e))
                    }
                }
            },
            onFailure = { error ->
                if (error is CatalogError) {
                    Result.failure(error)
                } else {
                    Result.failure(CatalogError.ProviderUnavailable(error.message ?: "Provider error", error))
                }
            },
        )
    }
}
