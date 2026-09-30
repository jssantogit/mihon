package tachiyomi.domain.tsuzuki.integration.model

data class CatalogRatingResolution(
    val externalId: String,
    val ratings: List<ExternalRating>,
)
