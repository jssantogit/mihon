package tachiyomi.domain.tsuzuki.catalog.model

import tachiyomi.domain.tsuzuki.integration.model.RatingIdentityEvidence

data class CatalogScore(
    val provider: String,
    val value: Double,
    val maxValue: Double = 100.0,
    val voteCount: Int? = null,
    val identityEvidence: RatingIdentityEvidence = RatingIdentityEvidence.VERIFIED,
)
