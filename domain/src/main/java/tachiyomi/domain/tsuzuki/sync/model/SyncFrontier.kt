package tachiyomi.domain.tsuzuki.sync.model

import kotlinx.serialization.Serializable

@Serializable
data class SyncFrontier(
    val entries: Map<String, Long> = emptyMap(),
) {
    init {
        require(entries.keys.none(String::isBlank)) {
            "Sync frontier device IDs must not be blank"
        }
        require(entries.values.all { it >= 0 }) {
            "Sync frontier sequences must not be negative"
        }
    }

    fun observes(revision: SyncRevision): Boolean =
        entries.getOrDefault(revision.deviceId, -1L) >= revision.sequence

    fun advance(revision: SyncRevision): SyncFrontier {
        val current = entries[revision.deviceId]
        if (current != null && current >= revision.sequence) return this
        return SyncFrontier(entries + (revision.deviceId to revision.sequence))
    }

    fun mergedWith(other: SyncFrontier): SyncFrontier {
        if (other.entries.isEmpty()) return this
        if (entries.isEmpty()) return other
        val merged = linkedMapOf<String, Long>()
        (entries.keys + other.entries.keys)
            .toSortedSet()
            .forEach { deviceId ->
                merged[deviceId] = maxOf(
                    entries[deviceId] ?: -1L,
                    other.entries[deviceId] ?: -1L,
                )
            }
        return SyncFrontier(merged)
    }
}
