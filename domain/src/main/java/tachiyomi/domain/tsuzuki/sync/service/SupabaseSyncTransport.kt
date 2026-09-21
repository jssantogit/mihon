package tachiyomi.domain.tsuzuki.sync.service

import tachiyomi.domain.tsuzuki.sync.model.SupabasePendingMutation
import tachiyomi.domain.tsuzuki.sync.model.SupabasePushResult
import tachiyomi.domain.tsuzuki.sync.model.SupabaseSyncCursor
import tachiyomi.domain.tsuzuki.sync.model.SupabaseSyncEvent
import tachiyomi.domain.tsuzuki.sync.model.SupabaseSyncSnapshot
import tachiyomi.domain.tsuzuki.sync.model.SupabaseMutationBatch
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncTransportResult

interface SupabaseSyncTransport {
    suspend fun pullDelta(
        documentKind: SyncDocumentKind,
        sinceEventId: Long,
        limit: Int = DEFAULT_DELTA_LIMIT,
    ): SyncTransportResult<List<SupabaseSyncEvent>>

    suspend fun snapshot(
        documentKind: SyncDocumentKind,
    ): SyncTransportResult<SupabaseSyncSnapshot>

    suspend fun push(
        batch: SupabaseMutationBatch,
    ): SyncTransportResult<SupabasePushResult>

    companion object {
        const val DEFAULT_DELTA_LIMIT = 500
    }
}

interface SupabaseSyncStateStore {
    suspend fun getCursor(documentKind: SyncDocumentKind): SupabaseSyncCursor?

    suspend fun putCursor(
        documentKind: SyncDocumentKind,
        eventCursor: Long,
        lastSuccessfulSyncAtEpochMillis: Long?,
    )

    suspend fun getPending(documentKind: SyncDocumentKind): SupabasePendingMutation?

    suspend fun putPending(mutation: SupabasePendingMutation)

    suspend fun recordPendingFailure(
        mutation: SupabasePendingMutation,
        nextAttemptAtEpochMillis: Long?,
    )

    suspend fun deletePending(mutationId: String)
}

fun interface SyncClientIdentityProvider {
    fun getOrCreate(): String
}
