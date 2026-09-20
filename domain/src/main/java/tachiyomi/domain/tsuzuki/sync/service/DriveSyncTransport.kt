package tachiyomi.domain.tsuzuki.sync.service

import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncRemoteContent
import tachiyomi.domain.tsuzuki.sync.model.SyncRemoteFile
import tachiyomi.domain.tsuzuki.sync.model.SyncTransportResult

interface DriveSyncTransport {

    suspend fun listFiles(): SyncTransportResult<List<SyncRemoteFile>>

    suspend fun download(file: SyncRemoteFile): SyncTransportResult<SyncRemoteContent>

    suspend fun generateFileId(): SyncTransportResult<String>

    suspend fun createReplica(
        remoteId: String,
        documentKind: SyncDocumentKind,
        ownerDeviceId: String,
        content: String,
    ): SyncTransportResult<SyncRemoteFile>

    suspend fun updateOwnedReplica(
        file: SyncRemoteFile,
        ownerDeviceId: String,
        content: String,
    ): SyncTransportResult<SyncRemoteFile>

    suspend fun getFile(remoteId: String): SyncTransportResult<SyncRemoteFile>

    suspend fun create(
        documentKind: SyncDocumentKind,
        content: String,
    ): SyncTransportResult<SyncRemoteFile>

    suspend fun update(
        file: SyncRemoteFile,
        content: String,
    ): SyncTransportResult<SyncRemoteFile>
}
