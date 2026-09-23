package tachiyomi.domain.tsuzuki.sync.model

data class SyncRemoteRevision(
    val remoteId: String,
    val revisionToken: String? = null,
    val modifiedAtEpochMillis: Long? = null,
) {
    init {
        require(remoteId.isNotBlank()) { "Sync remote ID must not be blank" }
        require(revisionToken == null || revisionToken.isNotBlank()) {
            "Sync remote revision token must not be blank"
        }
        require(modifiedAtEpochMillis == null || modifiedAtEpochMillis >= 0) {
            "Sync remote modifiedAt must not be negative"
        }
    }
}
