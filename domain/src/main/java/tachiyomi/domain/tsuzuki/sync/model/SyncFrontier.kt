package tachiyomi.domain.tsuzuki.sync.model

import kotlinx.serialization.Serializable

@Serializable
data class SyncFrontier(
    val entries: Map<String, Long> = emptyMap(),
) {
    fun observes(revision: SyncRevision): Boolean =
        error("Protocol v2 causal frontier not implemented")

    fun advance(revision: SyncRevision): SyncFrontier =
        error("Protocol v2 causal frontier not implemented")

    fun mergedWith(other: SyncFrontier): SyncFrontier =
        error("Protocol v2 causal frontier not implemented")
}
