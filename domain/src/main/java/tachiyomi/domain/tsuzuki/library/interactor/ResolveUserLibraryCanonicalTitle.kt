package tachiyomi.domain.tsuzuki.library.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitleFromCatalog
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import kotlin.time.Clock

class ResolveUserLibraryCanonicalTitle internal constructor(
    private val canonicalTitleRepository: CanonicalTitleRepository,
    private val sourceTitleMappingRepository: SourceTitleMappingRepository,
    private val trackRepository: TrackRepository,
    private val materializeCanonicalTitleFromCatalog: MaterializeCanonicalTitleFromCatalog,
    private val clock: () -> Long,
) {

    @Inject
    constructor(
        canonicalTitleRepository: CanonicalTitleRepository,
        sourceTitleMappingRepository: SourceTitleMappingRepository,
        trackRepository: TrackRepository,
        materializeCanonicalTitleFromCatalog: MaterializeCanonicalTitleFromCatalog,
    ) : this(
        canonicalTitleRepository = canonicalTitleRepository,
        sourceTitleMappingRepository = sourceTitleMappingRepository,
        trackRepository = trackRepository,
        materializeCanonicalTitleFromCatalog = materializeCanonicalTitleFromCatalog,
        clock = { Clock.System.now().toEpochMilliseconds() },
    )

    suspend fun execute(
        item: CatalogItem,
        legacyTrackerId: Long?,
    ): CanonicalTitle {
        canonicalTitleRepository
            .getByExternalIdentity(item.provider, item.providerId)
            ?.let { return materializeCanonicalTitleFromCatalog.execute(item) }

        val numericExternalId = item.providerId.toLongOrNull()
        if (legacyTrackerId != null && numericExternalId != null) {
            val mappingsByMangaId = sourceTitleMappingRepository
                .getAll()
                .mapNotNull { mapping ->
                    mapping.mihonMangaId?.let { mangaId -> mangaId to mapping.canonicalTitleId }
                }
                .groupBy(
                    keySelector = { it.first },
                    valueTransform = { it.second },
                )

            val candidateIds = trackRepository
                .getTracksAsFlow()
                .first()
                .asSequence()
                .filter { track ->
                    track.trackerId == legacyTrackerId && track.remoteId == numericExternalId
                }
                .flatMap { track -> mappingsByMangaId[track.mangaId].orEmpty().asSequence() }
                .distinct()
                .toList()

            if (candidateIds.size == 1) {
                val candidateId = candidateIds.single()
                val candidate = canonicalTitleRepository.getById(candidateId)
                if (candidate != null) {
                    val identity = ExternalIdentity(
                        canonicalTitleId = candidate.id,
                        provider = item.provider,
                        externalId = item.providerId,
                        verified = true,
                        createdAt = clock(),
                    )
                    try {
                        canonicalTitleRepository.addExternalIdentity(identity)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        canonicalTitleRepository
                            .getByExternalIdentity(item.provider, item.providerId)
                            ?: throw error
                    }
                }
            }
        }

        return materializeCanonicalTitleFromCatalog.execute(item)
    }
}
