package tachiyomi.domain.tsuzuki.integration.model

data class TsuzukiRatingSource(
    val providerId: String,
    val value: Double,
    val maxValue: Double,
    val voteCount: Int? = null,
    val identityEvidence: RatingIdentityEvidence = RatingIdentityEvidence.VERIFIED,
) {
    val normalizedValue: Double
        get() = (value / maxValue) * TSUZUKI_RATING_MAX
}

data class TsuzukiRating(
    val value: Double,
    val sources: List<TsuzukiRatingSource>,
    val maxValue: Double = TSUZUKI_RATING_MAX,
) {
    val sourceCount: Int
        get() = sources.size

    val verifiedSourceCount: Int
        get() = sources.count { it.identityEvidence == RatingIdentityEvidence.VERIFIED }

    val corroboratedSourceCount: Int
        get() = sourceCount - verifiedSourceCount
}

const val TSUZUKI_RATING_MAX = 10.0
const val TSUZUKI_RATING_MIN_SOURCES = 2
