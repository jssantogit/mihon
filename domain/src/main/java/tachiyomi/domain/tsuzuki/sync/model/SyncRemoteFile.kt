package tachiyomi.domain.tsuzuki.sync.model

data class SyncRemoteFile(
    val remoteId: String,
    val name: String,
    val mimeType: String?,
    val revision: SyncRemoteRevision,
    val protocolVersion: Int? = null,
    val logicalKind: SyncDocumentKind? = null,
    val ownerDeviceId: String? = null,
) {
    init {
        require(remoteId.isNotBlank()) { "Remote sync file ID must not be blank" }
        require(name.isNotBlank()) { "Remote sync file name must not be blank" }
        require(revision.remoteId == remoteId) {
            "Remote sync file revision must belong to the same remote ID"
        }
        require(protocolVersion == null || protocolVersion >= 1) {
            "Remote sync protocol version must be positive"
        }
        require(ownerDeviceId == null || ownerDeviceId.isNotBlank()) {
            "Remote sync owner device ID must not be blank"
        }
        require(
            protocolVersion != 2 ||
                (logicalKind != null && logicalKind != SyncDocumentKind.MANIFEST && ownerDeviceId != null),
        ) {
            "Protocol-v2 remote files require logical kind and owner metadata"
        }
    }
}

data class SyncRemoteContent(
    val file: SyncRemoteFile,
    val content: String,
)
