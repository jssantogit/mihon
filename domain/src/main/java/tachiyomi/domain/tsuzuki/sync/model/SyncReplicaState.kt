package tachiyomi.domain.tsuzuki.sync.model

data class SyncReplicaState(
    val documentKind: SyncDocumentKind,
    val ownerDeviceId: String,
    val reservedRemoteId: String?,
    val lastRemoteRevisionToken: String?,
    val nextSequence: Long,
) {
    init {
        require(ownerDeviceId.isNotBlank()) { "Sync replica owner device ID must not be blank" }
        require(reservedRemoteId == null || reservedRemoteId.isNotBlank()) {
            "Sync replica reserved remote ID must not be blank"
        }
        require(lastRemoteRevisionToken == null || lastRemoteRevisionToken.isNotBlank()) {
            "Sync replica remote revision token must not be blank"
        }
        require(nextSequence >= 1) { "Sync replica next sequence must be positive" }
    }
}
