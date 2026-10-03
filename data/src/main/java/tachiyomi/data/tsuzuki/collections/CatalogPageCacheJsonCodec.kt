package tachiyomi.data.tsuzuki.collections

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogScore

object CatalogPageCacheJsonCodec {

    private const val SCHEMA_VERSION = 2

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    fun encode(page: CatalogPage): String {
        return json.encodeToString(
            CachePayload(
                schemaVersion = SCHEMA_VERSION,
                items = page.items.map(CacheItem::fromDomain),
                hasNextPage = page.hasNextPage,
                totalCount = page.totalCount,
            ),
        )
    }

    fun decode(encoded: String): CatalogPage {
        val payload = json.decodeFromString<CachePayload>(encoded)
        require(payload.schemaVersion == SCHEMA_VERSION) {
            "Unsupported catalog cache payload schemaVersion: ${payload.schemaVersion}"
        }
        return CatalogPage(
            items = payload.items.map(CacheItem::toDomain),
            hasNextPage = payload.hasNextPage,
            totalCount = payload.totalCount,
        )
    }

    @Serializable
    private data class CachePayload(
        val schemaVersion: Int,
        val items: List<CacheItem>,
        val hasNextPage: Boolean,
        val totalCount: Int? = null,
    )

    @Serializable
    private data class CacheItem(
        val provider: String,
        val providerId: String,
        val title: String,
        val titles: Map<String, String> = emptyMap(),
        val synopsis: String? = null,
        val coverUrl: String? = null,
        val bannerUrl: String? = null,
        val status: String,
        val format: String,
        val score: CacheScore? = null,
        val authors: List<String> = emptyList(),
        val artists: List<String> = emptyList(),
        val genres: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val publishers: List<String> = emptyList(),
        val magazines: List<String> = emptyList(),
        val categories: List<String> = emptyList(),
        val demographics: List<String> = emptyList(),
        val country: String? = null,
        val popularity: Long? = null,
        val favorites: Long? = null,
        val rank: Double? = null,
        val startDate: String? = null,
        val endDate: String? = null,
        val chapterCount: Int? = null,
        val volumeCount: Int? = null,
    ) {
        fun toDomain(): CatalogItem = CatalogItem(
            provider = provider,
            providerId = providerId,
            title = title,
            titles = titles,
            synopsis = synopsis,
            coverUrl = coverUrl,
            bannerUrl = bannerUrl,
            status = CatalogItemStatus.valueOf(status),
            format = CatalogItemFormat.valueOf(format),
            score = score?.toDomain(),
            authors = authors,
            artists = artists,
            genres = genres,
            tags = tags,
            publishers = publishers,
            magazines = magazines,
            categories = categories,
            demographics = demographics,
            country = country,
            popularity = popularity,
            favorites = favorites,
            rank = rank,
            startDate = startDate,
            endDate = endDate,
            chapterCount = chapterCount,
            volumeCount = volumeCount,
        )

        companion object {
            fun fromDomain(item: CatalogItem): CacheItem = CacheItem(
                provider = item.provider,
                providerId = item.providerId,
                title = item.title,
                titles = item.titles,
                synopsis = item.synopsis,
                coverUrl = item.coverUrl,
                bannerUrl = item.bannerUrl,
                status = item.status.name,
                format = item.format.name,
                score = item.score?.let(CacheScore::fromDomain),
                authors = item.authors,
                artists = item.artists,
                genres = item.genres,
                tags = item.tags,
                publishers = item.publishers,
                magazines = item.magazines,
                categories = item.categories,
                demographics = item.demographics,
                country = item.country,
                popularity = item.popularity,
                favorites = item.favorites,
                rank = item.rank,
                startDate = item.startDate,
                endDate = item.endDate,
                chapterCount = item.chapterCount,
                volumeCount = item.volumeCount,
            )
        }
    }

    @Serializable
    private data class CacheScore(
        val provider: String,
        val value: Double,
        val maxValue: Double,
        val voteCount: Int? = null,
    ) {
        fun toDomain(): CatalogScore = CatalogScore(
            provider = provider,
            value = value,
            maxValue = maxValue,
            voteCount = voteCount,
        )

        companion object {
            fun fromDomain(score: CatalogScore): CacheScore = CacheScore(
                provider = score.provider,
                value = score.value,
                maxValue = score.maxValue,
                voteCount = score.voteCount,
            )
        }
    }
}
