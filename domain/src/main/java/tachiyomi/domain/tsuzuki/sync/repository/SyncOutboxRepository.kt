package tachiyomi.domain.tsuzuki.sync.repository

import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncOutboxEntry

interface SyncOutboxRepository {

    suspend fun markDirty(
        documentKind: SyncDocumentKind,
        enqueuedAtEpochMillis: Long,
    )

    suspend fun getPending(
        nowEpochMillis: Long,
        limit: Int,
    ): List<SyncOutboxEntry>

    suspend fun recordFailure(
        documentKind: SyncDocumentKind,
        nextAttemptAtEpochMillis: Long?,
    )

    suspend fun clear(documentKind: SyncDocumentKind)
}
