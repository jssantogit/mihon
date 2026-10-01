package tachiyomi.domain.tsuzuki.integration.model

data class CatalogRatingMatch(
    val externalId: String,
    val rating: ExternalRating,
    /**
     * True only when the provider id was already known or proven by a provider-published mapping.
     * Rating-only discovery may return a strict corroborated match without promoting that id into
     * canonical cross-provider identity.
     */
    val verifiedIdentity: Boolean = true,
)
