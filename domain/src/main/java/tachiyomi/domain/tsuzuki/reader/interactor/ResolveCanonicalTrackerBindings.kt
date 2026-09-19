package tachiyomi.domain.tsuzuki.reader.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository

@Inject
class ResolveCanonicalTrackerBindings(
    private val sourceTitleMappingRepository: SourceTitleMappingRepository,
    private val trackRepository: TrackRepository,
) {

    suspend fun execute(canonicalTitleId: String): List<Track> {
        val mappings = sourceTitleMappingRepository
            .getByCanonicalTitleId(canonicalTitleId)
            .filter { it.mihonMangaId != null }
            .sortedWith(
                compareBy<SourceTitleMapping> { if (it.preferredOverride) 0 else 1 }
                    .thenBy { if (it.verifiedByUser) 0 else 1 }
                    .thenBy { it.id },
            )

        val selectedByTracker = linkedMapOf<Long, Track>()
        for (mapping in mappings) {
            val mangaId = mapping.mihonMangaId ?: continue
            val tracks = try {
                trackRepository.getTracksByMangaId(mangaId)
            } catch (_: Throwable) {
                emptyList()
            }
            for (track in tracks) {
                selectedByTracker.putIfAbsent(track.trackerId, track)
            }
        }

        return selectedByTracker.values.toList()
    }
}
