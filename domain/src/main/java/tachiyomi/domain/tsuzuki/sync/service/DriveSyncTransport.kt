package tachiyomi.domain.tsuzuki.sync.service

import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncRemoteContent
import tachiyomi.domain.tsuzuki.sync.model.SyncRemoteFile
import tachiyomi.domain.tsuzuki.sync.model.SyncTransportResult

interface DriveSyncTransport {

    suspend fun listFiles(): SyncTransportResult<List<SyncRemoteFile>>

    suspend fun download(file: SyncRemoteFile): SyncTransportResult<SyncRemoteContent>

    suspend fun create(
        documentKind: SyncDocumentKind,
        content: String,
    ): SyncTransportResult<SyncRemoteFile>

    suspend fun update(
        file: SyncRemoteFile,
        content: String,
    ): SyncTransportResult<SyncRemoteFile>
}
