package tachiyomi.domain.tsuzuki.sync.model

data class SyncRemoteFile(
    val remoteId: String,
    val name: String,
    val mimeType: String?,
    val revision: SyncRemoteRevision,
) {
    init {
        require(remoteId.isNotBlank()) { "Remote sync file ID must not be blank" }
        require(name.isNotBlank()) { "Remote sync file name must not be blank" }
        require(revision.remoteId == remoteId) {
            "Remote sync file revision must belong to the same remote ID"
        }
    }
}

data class SyncRemoteContent(
    val file: SyncRemoteFile,
    val content: String,
)
