package tachiyomi.domain.tsuzuki.reader.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository

@Inject
class DeleteCanonicalTrackerBinding(
    private val sourceTitleMappingRepository: SourceTitleMappingRepository,
    private val trackRepository: TrackRepository,
) {

    suspend fun executeForManga(
        mangaId: Long,
        trackerId: Long,
    ): Boolean {
        val canonicalTitleId = sourceTitleMappingRepository
            .getAll()
            .firstOrNull { it.mihonMangaId == mangaId }
            ?.canonicalTitleId
            ?: return false

        execute(canonicalTitleId, trackerId)
        return true
    }

    suspend fun execute(
        canonicalTitleId: String,
        trackerId: Long,
    ) {
        sourceTitleMappingRepository
            .getByCanonicalTitleId(canonicalTitleId)
            .mapNotNull { it.mihonMangaId }
            .distinct()
            .forEach { mangaId ->
                trackRepository.delete(mangaId, trackerId)
            }
    }
}
