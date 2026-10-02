package tachiyomi.data.tsuzuki.integration

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.model.ProvenancedMetadata
import tachiyomi.domain.tsuzuki.integration.model.RatingIdentityEvidence
import tachiyomi.domain.tsuzuki.integration.model.ResolvedMetadata
import tachiyomi.domain.tsuzuki.integration.model.ResolvedRating
import tachiyomi.domain.tsuzuki.integration.model.TsuzukiRating
import tachiyomi.domain.tsuzuki.integration.model.TsuzukiRatingSource

object CanonicalMetadataSnapshotJsonCodec {

    private const val SCHEMA_VERSION = 1

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    fun encode(metadata: ResolvedMetadata): String = json.encodeToString(
        Payload(
            schemaVersion = SCHEMA_VERSION,
            title = metadata.title?.toCache(),
            synopsis = metadata.synopsis?.toCache(),
            artworkUrl = metadata.artworkUrl?.toCache(),
            status = metadata.status?.toCache(),
            format = metadata.format?.toCache(),
            editorialChapterCount = metadata.editorialChapterCount?.toCache(),
            rating = metadata.rating?.toCache(),
            ratingDetails = metadata.ratingDetails?.toCache { it.toCache() },
            ratings = metadata.ratings.map { it.toCache { rating -> rating.toCache() } },
            tsuzukiRating = metadata.tsuzukiRating?.toCache(),
            authors = metadata.authors?.toCache(),
            artists = metadata.artists?.toCache(),
            genres = metadata.genres?.toCache(),
            tags = metadata.tags?.toCache(),
            startDate = metadata.startDate?.toCache(),
            endDate = metadata.endDate?.toCache(),
            editorialVolumeCount = metadata.editorialVolumeCount?.toCache(),
            externalIds = metadata.externalIds.mapKeys { (key, _) -> key.value },
        ),
    )

    fun decode(encoded: String): ResolvedMetadata {
        val payload = json.decodeFromString<Payload>(encoded)
        require(payload.schemaVersion == SCHEMA_VERSION) {
            "Unsupported metadata snapshot schemaVersion: ${payload.schemaVersion}"
        }
        return ResolvedMetadata(
            title = payload.title?.toDomain(),
            synopsis = payload.synopsis?.toDomain(),
            artworkUrl = payload.artworkUrl?.toDomain(),
            status = payload.status?.toDomain(),
            format = payload.format?.toDomain(),
            editorialChapterCount = payload.editorialChapterCount?.toDomain(),
            rating = payload.rating?.toDomain(),
            ratingDetails = payload.ratingDetails?.toDomain { it.toDomain() },
            ratings = payload.ratings.map { it.toDomain { rating -> rating.toDomain() } },
            tsuzukiRating = payload.tsuzukiRating?.toDomain(),
            authors = payload.authors?.toDomain(),
            artists = payload.artists?.toDomain(),
            genres = payload.genres?.toDomain(),
            tags = payload.tags?.toDomain(),
            startDate = payload.startDate?.toDomain(),
            endDate = payload.endDate?.toDomain(),
            editorialVolumeCount = payload.editorialVolumeCount?.toDomain(),
            externalIds = payload.externalIds.mapKeys { (key, _) -> IntegrationId(key) },
        )
    }

    private fun <T> ProvenancedMetadata<T>.toCache(): Field<T> = Field(
        value = value,
        providerId = providerId.value,
        externalId = externalId,
        attribution = attribution,
    )

    private fun <T, R> ProvenancedMetadata<T>.toCache(transform: (T) -> R): Field<R> = Field(
        value = transform(value),
        providerId = providerId.value,
        externalId = externalId,
        attribution = attribution,
    )

    private fun <T> Field<T>.toDomain(): ProvenancedMetadata<T> = ProvenancedMetadata(
        value = value,
        providerId = IntegrationId(providerId),
        externalId = externalId,
        attribution = attribution,
    )

    private fun <T, R> Field<T>.toDomain(transform: (T) -> R): ProvenancedMetadata<R> = ProvenancedMetadata(
        value = transform(value),
        providerId = IntegrationId(providerId),
        externalId = externalId,
        attribution = attribution,
    )

    private fun ResolvedRating.toCache() = RatingPayload(
        value = value,
        maxValue = maxValue,
        voteCount = voteCount,
        identityEvidence = identityEvidence.name,
    )

    private fun RatingPayload.toDomain() = ResolvedRating(
        value = value,
        maxValue = maxValue,
        voteCount = voteCount,
        identityEvidence = RatingIdentityEvidence.valueOf(identityEvidence),
    )

    private fun TsuzukiRating.toCache() = TsuzukiRatingPayload(
        value = value,
        maxValue = maxValue,
        sources = sources.map { source ->
            TsuzukiRatingSourcePayload(
                providerId = source.providerId,
                value = source.value,
                maxValue = source.maxValue,
                voteCount = source.voteCount,
                identityEvidence = source.identityEvidence.name,
            )
        },
    )

    private fun TsuzukiRatingPayload.toDomain() = TsuzukiRating(
        value = value,
        maxValue = maxValue,
        sources = sources.map { source ->
            TsuzukiRatingSource(
                providerId = source.providerId,
                value = source.value,
                maxValue = source.maxValue,
                voteCount = source.voteCount,
                identityEvidence = RatingIdentityEvidence.valueOf(source.identityEvidence),
            )
        },
    )

    @Serializable
    private data class Payload(
        val schemaVersion: Int,
        val title: Field<String>? = null,
        val synopsis: Field<String>? = null,
        val artworkUrl: Field<String>? = null,
        val status: Field<String>? = null,
        val format: Field<String>? = null,
        val editorialChapterCount: Field<Int>? = null,
        val rating: Field<Double>? = null,
        val ratingDetails: Field<RatingPayload>? = null,
        val ratings: List<Field<RatingPayload>> = emptyList(),
        val tsuzukiRating: TsuzukiRatingPayload? = null,
        val authors: Field<List<String>>? = null,
        val artists: Field<List<String>>? = null,
        val genres: Field<List<String>>? = null,
        val tags: Field<List<String>>? = null,
        val startDate: Field<String>? = null,
        val endDate: Field<String>? = null,
        val editorialVolumeCount: Field<Int>? = null,
        val externalIds: Map<String, String> = emptyMap(),
    )

    @Serializable
    private data class Field<T>(
        val value: T,
        val providerId: String,
        val externalId: String? = null,
        val attribution: String? = null,
    )

    @Serializable
    private data class RatingPayload(
        val value: Double,
        val maxValue: Double,
        val voteCount: Int? = null,
        val identityEvidence: String,
    )

    @Serializable
    private data class TsuzukiRatingPayload(
        val value: Double,
        val maxValue: Double,
        val sources: List<TsuzukiRatingSourcePayload>,
    )

    @Serializable
    private data class TsuzukiRatingSourcePayload(
        val providerId: String,
        val value: Double,
        val maxValue: Double,
        val voteCount: Int? = null,
        val identityEvidence: String,
    )
}
