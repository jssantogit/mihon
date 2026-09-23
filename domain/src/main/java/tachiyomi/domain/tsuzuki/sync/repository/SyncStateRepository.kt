package tachiyomi.domain.tsuzuki.sync.repository

import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncStoredState

interface SyncStateRepository {

    suspend fun get(documentKind: SyncDocumentKind): SyncStoredState?

    suspend fun put(state: SyncStoredState)

    suspend fun clear(documentKind: SyncDocumentKind)
}
