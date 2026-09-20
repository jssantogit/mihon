package tachiyomi.domain.tsuzuki.sync.repository

import tachiyomi.domain.tsuzuki.sync.model.StoredSyncConflict
import tachiyomi.domain.tsuzuki.sync.model.SyncConflict
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind

interface SyncConflictRepository {

    suspend fun replaceForDocument(
        documentKind: SyncDocumentKind,
        conflicts: List<SyncConflict>,
        createdAtEpochMillis: Long,
    )

    suspend fun getForDocument(
        documentKind: SyncDocumentKind,
    ): List<StoredSyncConflict>

    suspend fun clearForDocument(documentKind: SyncDocumentKind)
}
