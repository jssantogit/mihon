package tachiyomi.domain.tsuzuki.sync.repository

import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncReplicaState

interface SyncReplicaRepository {

    suspend fun get(
        documentKind: SyncDocumentKind,
        ownerDeviceId: String,
    ): SyncReplicaState?

    suspend fun put(state: SyncReplicaState)

    suspend fun clear(
        documentKind: SyncDocumentKind,
        ownerDeviceId: String,
    )
}
