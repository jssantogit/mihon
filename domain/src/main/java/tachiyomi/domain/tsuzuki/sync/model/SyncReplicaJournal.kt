package tachiyomi.domain.tsuzuki.sync.model

import kotlinx.serialization.Serializable

@Serializable
data class SyncReplicaGenesis(
    val sourceRemoteId: String,
    val sourceRevisionToken: String?,
    val document: SyncDocumentEnvelope,
) {
    init {
        require(sourceRemoteId.isNotBlank()) { "Replica genesis remote ID must not be blank" }
        require(sourceRevisionToken == null || sourceRevisionToken.isNotBlank()) {
            "Replica genesis revision token must not be blank"
        }
    }
}

@Serializable
data class SyncReplicaJournal(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val kind: SyncDocumentKind,
    val ownerDeviceId: String,
    val genesis: SyncReplicaGenesis? = null,
    val batches: List<SyncMutationBatch>,
) {
    init {
        require(protocolVersion == PROTOCOL_VERSION) {
            "Unsupported replica journal protocol version"
        }
        require(ownerDeviceId.isNotBlank()) { "Replica owner device ID must not be blank" }
        require(kind != SyncDocumentKind.MANIFEST) {
            "Manifest is not a protocol-v2 replica journal"
        }
        require(genesis == null || genesis.document.kind == kind) {
            "Replica genesis must match journal kind"
        }

        var previous = -1L
        batches.forEach { batch ->
            require(batch.revision.deviceId == ownerDeviceId) {
                "Replica journal batch must belong to its owner"
            }
            require(batch.revision.sequence > previous) {
                "Replica journal batch sequences must strictly increase"
            }
            require((batch.observed.entries[ownerDeviceId] ?: -1L) < batch.revision.sequence) {
                "Replica journal batch cannot observe its own revision or a later sequence"
            }
            previous = batch.revision.sequence
        }
    }

    companion object {
        const val PROTOCOL_VERSION = 2
    }
}
