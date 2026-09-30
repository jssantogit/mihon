package tachiyomi.data.tsuzuki.artwork

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.tsuzuki.artwork.model.TitleArtworkObservation
import tachiyomi.domain.tsuzuki.artwork.repository.TitleArtworkRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class TitleArtworkRepositoryImpl(
    private val database: Database,
) : TitleArtworkRepository {

    override fun observeAll(): Flow<List<TitleArtworkObservation>> =
        database.tsuzuki_title_artwork_observationsQueries
            .getAllTsuzukiTitleArtworkObservations(::mapObservation)
            .subscribeToList()

    override suspend fun getByTitle(
        canonicalTitleId: String,
    ): List<TitleArtworkObservation> =
        database.tsuzuki_title_artwork_observationsQueries
            .getTsuzukiTitleArtworkObservationsByTitle(
                canonicalTitleId,
                ::mapObservation,
            )
            .awaitAsList()

    override suspend fun upsert(observation: TitleArtworkObservation) {
        database.tsuzuki_title_artwork_observationsQueries
            .upsertTsuzukiTitleArtworkObservation(
                canonicalTitleId = observation.canonicalTitleId,
                provider = observation.provider,
                coverUrl = observation.coverUrl,
                bannerUrl = observation.bannerUrl,
                updatedAt = observation.updatedAt,
            )
    }

    private fun mapObservation(
        canonicalTitleId: String,
        provider: String,
        coverUrl: String?,
        bannerUrl: String?,
        updatedAt: Long,
    ) = TitleArtworkObservation(
        canonicalTitleId = canonicalTitleId,
        provider = provider,
        coverUrl = coverUrl,
        bannerUrl = bannerUrl,
        updatedAt = updatedAt,
    )
}
