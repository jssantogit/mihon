package tachiyomi.domain.tsuzuki.sync.model

enum class SyncConflictResolutionChoice {
    KEEP_LOCAL,
    KEEP_REMOTE,
}

sealed interface SyncConflictResolutionResult {
    data object Resolved : SyncConflictResolutionResult

    data class StillConflicted(
        val conflictCount: Int,
    ) : SyncConflictResolutionResult {
        init {
            require(conflictCount > 0) { "Conflict count must be positive" }
        }
    }

    data class Failed(
        val failure: SyncFailure,
    ) : SyncConflictResolutionResult
}
