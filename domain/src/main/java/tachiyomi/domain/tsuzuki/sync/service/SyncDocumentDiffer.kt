package tachiyomi.domain.tsuzuki.sync.service

import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncMutation

class SyncDocumentDiffer {

    fun diff(
        base: SyncDocumentEnvelope?,
        current: SyncDocumentEnvelope,
    ): List<SyncMutation> = error("Protocol v2 document differ not implemented")
}
