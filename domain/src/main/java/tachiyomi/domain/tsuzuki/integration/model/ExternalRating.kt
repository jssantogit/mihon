package tachiyomi.domain.tsuzuki.integration.model

data class ExternalRating(
    val providerId: String,
    val label: String,
    val value: Double,
    val scaleMax: Double,
)
