package tachiyomi.data.tsuzuki

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.library.model.TitleFormatObservation
import tachiyomi.domain.tsuzuki.repository.TitleFormatObservationRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class TitleFormatObservationRepositoryImpl(
    private val database: Database,
) : TitleFormatObservationRepository {

    override fun observeAll(): Flow<List<TitleFormatObservation>> =
        database.tsuzuki_title_format_observationsQueries
            .getAllTsuzukiTitleFormatObservations(::mapObservation)
            .subscribeToList()

    override suspend fun upsert(observation: TitleFormatObservation) {
        database.tsuzuki_title_format_observationsQueries
            .upsertTsuzukiTitleFormatObservation(
                canonicalTitleId = observation.canonicalTitleId,
                provider = observation.provider,
                format = observation.format.name,
                updatedAt = observation.updatedAt,
            )
    }

    private fun mapObservation(
        canonicalTitleId: String,
        provider: String,
        format: String,
        updatedAt: Long,
    ) = TitleFormatObservation(
        canonicalTitleId = canonicalTitleId,
        provider = provider,
        format = CatalogItemFormat.valueOf(format),
        updatedAt = updatedAt,
    )
}
