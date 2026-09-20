package tachiyomi.domain.tsuzuki.sync.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class SyncRecordEnvelope(
    val id: String,
    val revision: SyncRevision,
    val updatedAtEpochMillis: Long,
    val deletedAtEpochMillis: Long? = null,
    val fields: JsonObject,
) {
    init {
        require(id.isNotBlank()) { "Sync record ID must not be blank" }
        require(updatedAtEpochMillis >= 0) { "Sync record updatedAt must not be negative" }
        require(deletedAtEpochMillis == null || deletedAtEpochMillis >= 0) {
            "Sync record deletedAt must not be negative"
        }
    }

    val isTombstone: Boolean
        get() = deletedAtEpochMillis != null
}
