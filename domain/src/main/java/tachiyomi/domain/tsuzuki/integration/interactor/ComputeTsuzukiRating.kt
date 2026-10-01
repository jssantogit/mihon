package tachiyomi.domain.tsuzuki.integration.interactor

import tachiyomi.domain.tsuzuki.integration.model.TSUZUKI_RATING_MAX
import tachiyomi.domain.tsuzuki.integration.model.TSUZUKI_RATING_MIN_SOURCES
import tachiyomi.domain.tsuzuki.integration.model.TsuzukiRating
import tachiyomi.domain.tsuzuki.integration.model.TsuzukiRatingSource

/**
 * Produces the Tsuzuki Rating from provider-native community ratings.
 *
 * V1 deliberately gives every provider one equal vote after scale normalization. Raw vote counts
 * are retained as provenance/confidence data but never weight the aggregate.
 */
object ComputeTsuzukiRating {

    operator fun invoke(
        sources: List<TsuzukiRatingSource>,
        minimumSources: Int = TSUZUKI_RATING_MIN_SOURCES,
    ): TsuzukiRating? {
        val usable = sources
            .asSequence()
            .filter { it.isValid() }
            .distinctBy(TsuzukiRatingSource::providerId)
            .toList()

        if (usable.size < minimumSources.coerceAtLeast(TSUZUKI_RATING_MIN_SOURCES)) {
            return null
        }

        val normalized = usable.map { source ->
            (source.value / source.maxValue) * TSUZUKI_RATING_MAX
        }
        val value = normalized.average()
        if (!value.isFinite()) return null

        return TsuzukiRating(
            value = value,
            sources = usable,
        )
    }

    private fun TsuzukiRatingSource.isValid(): Boolean =
        providerId.isNotBlank() &&
            value.isFinite() &&
            maxValue.isFinite() &&
            maxValue > 0.0 &&
            value >= 0.0 &&
            value <= maxValue
}
