package tachiyomi.domain.tsuzuki.sync.model

import kotlinx.serialization.Serializable

@Serializable
data class SyncReplicaGenesis(
    val sourceRemoteId: String,
    val sourceRevisionToken: String?,
    val document: SyncDocumentEnvelope,
)

@Serializable
data class SyncReplicaJournal(
    val protocolVersion: Int = 2,
    val kind: SyncDocumentKind,
    val ownerDeviceId: String,
    val genesis: SyncReplicaGenesis? = null,
    val batches: List<SyncMutationBatch>,
)
