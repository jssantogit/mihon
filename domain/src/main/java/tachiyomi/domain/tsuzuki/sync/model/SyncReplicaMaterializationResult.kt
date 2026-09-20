package tachiyomi.domain.tsuzuki.sync.model

sealed interface SyncReplicaMaterializationResult {

    data class Success(
        val document: SyncDocumentEnvelope,
        val frontier: SyncFrontier,
        val conflicts: List<SyncConflict>,
    ) : SyncReplicaMaterializationResult

    data class Failure(
        val failure: SyncFailure,
    ) : SyncReplicaMaterializationResult
}
