package tachiyomi.domain.tsuzuki.sync.model

data class StoredSyncConflict(
    val conflict: SyncConflict,
    val createdAtEpochMillis: Long,
) {
    init {
        require(createdAtEpochMillis >= 0) {
            "Sync conflict creation time must not be negative"
        }
    }
}
