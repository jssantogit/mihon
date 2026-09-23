package tachiyomi.domain.tsuzuki.reader.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.reader.model.CanonicalTrackerBindingResolution
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository

@Inject
class ObserveCanonicalTrackerBindingsForManga(
    private val sourceTitleMappingRepository: SourceTitleMappingRepository,
    private val trackRepository: TrackRepository,
) {

    suspend fun execute(mangaId: Long): Flow<CanonicalTrackerBindingResolution> {
        val canonicalTitleId = sourceTitleMappingRepository
            .getAll()
            .firstOrNull { it.mihonMangaId == mangaId }
            ?.canonicalTitleId
            ?: return flowOf(CanonicalTrackerBindingResolution(emptyList()))

        return combine(
            sourceTitleMappingRepository.getByCanonicalTitleIdAsFlow(canonicalTitleId),
            trackRepository.getTracksAsFlow(),
        ) { mappings, tracks ->
            resolveBindings(
                mappings = mappings,
                tracks = tracks,
            )
        }
    }

    private fun resolveBindings(
        mappings: List<SourceTitleMapping>,
        tracks: List<Track>,
    ): CanonicalTrackerBindingResolution {
        val selectedByTracker = linkedMapOf<Long, Track>()
        val conflicts = linkedSetOf<Long>()

        mappings
            .filter { it.mihonMangaId != null }
            .sortedWith(
                compareBy<SourceTitleMapping> { if (it.preferredOverride) 0 else 1 }
                    .thenBy { if (it.verifiedByUser) 0 else 1 }
                    .thenBy { it.id },
            )
            .forEach { mapping ->
                val mangaId = mapping.mihonMangaId ?: return@forEach
                tracks
                    .filter { it.mangaId == mangaId }
                    .forEach { track ->
                        if (track.trackerId in conflicts) return@forEach

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
