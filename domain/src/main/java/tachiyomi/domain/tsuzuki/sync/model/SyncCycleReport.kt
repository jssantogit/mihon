package tachiyomi.domain.tsuzuki.sync.model

data class SyncCycleReport(
    val documentResults: List<SyncDocumentResult>,
    val globalFailure: SyncFailure? = null,
) {
    val hasConflicts: Boolean
        get() = documentResults.any { it is SyncDocumentResult.Conflict }

    val hasFailures: Boolean
        get() = globalFailure != null ||
            documentResults.any { it is SyncDocumentResult.Failed }
}

sealed interface SyncDocumentResult {
    val documentKind: SyncDocumentKind

    data class Synchronized(
        override val documentKind: SyncDocumentKind,
        val localApplied: Boolean,
        val remoteWritten: Boolean,
    ) : SyncDocumentResult

    data class Conflict(
        override val documentKind: SyncDocumentKind,
        val conflictCount: Int,
    ) : SyncDocumentResult

    data class Failed(
        override val documentKind: SyncDocumentKind,
        val failure: SyncFailure,
    ) : SyncDocumentResult

    data class Deferred(
        override val documentKind: SyncDocumentKind,
        val nextAttemptAtEpochMillis: Long,
    ) : SyncDocumentResult
}
