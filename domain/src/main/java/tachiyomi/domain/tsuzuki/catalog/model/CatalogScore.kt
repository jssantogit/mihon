package tachiyomi.domain.tsuzuki.catalog.model

data class CatalogScore(
    val provider: String,
    val value: Double,
    val maxValue: Double = 100.0,
    val voteCount: Int? = null,
)
