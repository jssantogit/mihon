package tachiyomi.domain.tsuzuki.artwork.model

data class TitleArtworkObservation(
    val canonicalTitleId: String,
    val provider: String,
    val coverUrl: String? = null,
    val bannerUrl: String? = null,
    val updatedAt: Long,
) {
    init {
        require(canonicalTitleId.isNotBlank()) { "Canonical title id cannot be blank" }
        require(provider.isNotBlank()) { "Artwork provider cannot be blank" }
    }
}

data class ResolvedCanonicalArtwork(
    val coverUrl: String?,
    val coverProvider: String?,
    val bannerUrl: String?,
    val bannerProvider: String?,
)
