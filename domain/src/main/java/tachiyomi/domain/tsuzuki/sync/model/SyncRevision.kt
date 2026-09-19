package tachiyomi.domain.tsuzuki.sync.model

import kotlinx.serialization.Serializable

@Serializable
data class SyncRevision(
    val deviceId: String,
    val sequence: Long,
) {
    init {
        require(deviceId.isNotBlank()) { "Sync revision device ID must not be blank" }
        require(sequence >= 0) { "Sync revision sequence must not be negative" }
    }
}
