package tachiyomi.domain.tsuzuki.sync.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
sealed interface SyncMutation {
    val recordId: String
    val recordUpdatedAtEpochMillis: Long

    @Serializable
    @SerialName("set_field")
    data class SetField(
        override val recordId: String,
        val propertyPath: List<String>,
        val value: JsonElement,
        override val recordUpdatedAtEpochMillis: Long,
    ) : SyncMutation {
        init {
            validateRecordMutation(recordId, propertyPath, recordUpdatedAtEpochMillis)
        }
    }

    @Serializable
    @SerialName("remove_field")
    data class RemoveField(
        override val recordId: String,
        val propertyPath: List<String>,
        override val recordUpdatedAtEpochMillis: Long,
    ) : SyncMutation {
        init {
            validateRecordMutation(recordId, propertyPath, recordUpdatedAtEpochMillis)
        }
    }

    @Serializable
    @SerialName("delete_record")
    data class DeleteRecord(
        override val recordId: String,
        override val recordUpdatedAtEpochMillis: Long,
        val deletedAtEpochMillis: Long,
    ) : SyncMutation {
        init {
            require(recordId.isNotBlank()) { "Sync mutation record ID must not be blank" }
            require(recordUpdatedAtEpochMillis >= 0) {
                "Sync mutation record updatedAt must not be negative"
            }
            require(deletedAtEpochMillis >= 0) {
                "Sync mutation deletedAt must not be negative"
            }
        }
    }
}

@Serializable
data class SyncMutationBatch(
    val revision: SyncRevision,
    val observed: SyncFrontier,
    val generatedAtEpochMillis: Long,
    val mutations: List<SyncMutation>,
) {
    init {
        require(generatedAtEpochMillis >= 0) {
            "Sync mutation batch generatedAt must not be negative"
        }
        require((observed.entries[revision.deviceId] ?: -1L) < revision.sequence) {
            "Sync mutation batch cannot observe its own revision or a later local sequence"
        }
    }
}

private fun validateRecordMutation(
    recordId: String,
    propertyPath: List<String>,
    recordUpdatedAtEpochMillis: Long,
) {
    require(recordId.isNotBlank()) { "Sync mutation record ID must not be blank" }
    require(propertyPath.isNotEmpty() && propertyPath.none(String::isBlank)) {
        "Sync field mutation path must contain nonblank segments"
    }
    require(recordUpdatedAtEpochMillis >= 0) {
        "Sync mutation record updatedAt must not be negative"
    }
}
