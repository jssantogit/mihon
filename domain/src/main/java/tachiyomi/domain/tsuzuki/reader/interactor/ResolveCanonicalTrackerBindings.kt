package tachiyomi.domain.tsuzuki.reader.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.reader.model.CanonicalTrackerBindingResolution
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository

@Inject
class ResolveCanonicalTrackerBindings(
    private val sourceTitleMappingRepository: SourceTitleMappingRepository,
    private val trackRepository: TrackRepository,
) {

    suspend fun execute(canonicalTitleId: String): CanonicalTrackerBindingResolution {
        val mappings = sourceTitleMappingRepository
            .getByCanonicalTitleId(canonicalTitleId)
            .filter { it.mihonMangaId != null }
            .sortedWith(
                compareBy<SourceTitleMapping> { if (it.preferredOverride) 0 else 1 }
                    .thenBy { if (it.verifiedByUser) 0 else 1 }
                    .thenBy { it.id },
            )

        val selectedByTracker = linkedMapOf<Long, Track>()
        val conflicts = linkedSetOf<Long>()

        for (mapping in mappings) {
            val mangaId = mapping.mihonMangaId ?: continue
            val tracks = try {
                trackRepository.getTracksByMangaId(mangaId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                emptyList()
            }

            for (track in tracks) {
                if (track.trackerId in conflicts) continue

                val existing = selectedByTracker[track.trackerId]
                when {
                    existing == null -> selectedByTracker[track.trackerId] = track
                    existing.remoteId == track.remoteId -> Unit
                    else -> {
                        selectedByTracker.remove(track.trackerId)
                        conflicts += track.trackerId
                    }
                }
            }
        }

        return CanonicalTrackerBindingResolution(
            tracks = selectedByTracker.values.toList(),
            conflictingTrackerIds = conflicts,
        )
    }
}
