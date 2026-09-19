package tachiyomi.domain.tsuzuki.reader.model

import tachiyomi.domain.track.model.Track

data class CanonicalTrackerBindingResolution(
    val tracks: List<Track>,
    val conflictingTrackerIds: Set<Long> = emptySet(),
)
