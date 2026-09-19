package tachiyomi.domain.tsuzuki.sync.model

sealed interface SyncMergeResult {

    data class Success(
        val outcome: SyncMergeOutcome,
    ) : SyncMergeResult

    data class Rejected(
        val failure: SyncFailure,
    ) : SyncMergeResult
}

data class SyncMergeOutcome(
    val documentKind: SyncDocumentKind,
    val schemaVersion: Int,
    val records: Map<String, SyncRecordEnvelope>,
    val conflicts: List<SyncConflict>,
    val requiresLocalApply: Boolean,
    val requiresRemoteWrite: Boolean,
) {
    val hasConflicts: Boolean
        get() = conflicts.isNotEmpty()
}
