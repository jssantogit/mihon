package tachiyomi.domain.tsuzuki.artwork

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.artwork.model.ResolvedCanonicalArtwork
import tachiyomi.domain.tsuzuki.artwork.model.TitleArtworkObservation
import tachiyomi.domain.tsuzuki.artwork.repository.TitleArtworkRepository

@Inject
class ResolveCanonicalArtwork(
    private val repository: TitleArtworkRepository,
) {

    suspend fun execute(canonicalTitleId: String): ResolvedCanonicalArtwork? =
        resolveCanonicalArtwork(repository.getByTitle(canonicalTitleId))
}

fun resolveCanonicalArtwork(
    observations: List<TitleArtworkObservation>,
): ResolvedCanonicalArtwork? {
    val ordered = observations.sortedWith(
        compareBy<TitleArtworkObservation>(
            { observation ->
                ARTWORK_PROVIDER_PRECEDENCE.indexOf(observation.provider)
                    .takeIf { it >= 0 }
                    ?: Int.MAX_VALUE
            },
            TitleArtworkObservation::provider,
            { -it.updatedAt },
        ),
    )
    val cover = ordered.firstOrNull { !it.coverUrl.isNullOrBlank() }
    val banner = ordered.firstOrNull { !it.bannerUrl.isNullOrBlank() }
    if (cover == null && banner == null) return null

    return ResolvedCanonicalArtwork(
        coverUrl = cover?.coverUrl,
        coverProvider = cover?.provider,
        bannerUrl = banner?.bannerUrl,
        bannerProvider = banner?.provider,
    )
}

private val ARTWORK_PROVIDER_PRECEDENCE = listOf(
    "kitsu",
    "mal",
    "mangaupdates",
    "bangumi",
)
