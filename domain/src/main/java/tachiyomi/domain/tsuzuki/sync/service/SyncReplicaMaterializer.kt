package tachiyomi.domain.tsuzuki.sync.service

import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncFrontier
import tachiyomi.domain.tsuzuki.sync.model.SyncReplicaJournal
import tachiyomi.domain.tsuzuki.sync.model.SyncReplicaMaterializationResult

class SyncReplicaMaterializer {

    fun materialize(
        kind: SyncDocumentKind,
        schemaVersion: Int,
        journals: List<SyncReplicaJournal>,
        materializedAtEpochMillis: Long,
        visibleFrontier: SyncFrontier? = null,
    ): SyncReplicaMaterializationResult =
        error("Protocol v2 replica materializer not implemented")
}
