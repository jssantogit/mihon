package tachiyomi.domain.tsuzuki.sync.model

data class SyncStoredState(
    val documentKind: SyncDocumentKind,
    val acceptedBase: SyncDocumentEnvelope?,
    val remoteRevision: SyncRemoteRevision?,
    val lastSuccessfulSyncAtEpochMillis: Long?,
) {
    init {
        require(acceptedBase == null || acceptedBase.kind == documentKind) {
            "Stored sync base must match its logical document kind"
        }
        require(
            lastSuccessfulSyncAtEpochMillis == null ||
                lastSuccessfulSyncAtEpochMillis >= 0,
        ) {
            "Last successful sync time must not be negative"
        }
    }
}
