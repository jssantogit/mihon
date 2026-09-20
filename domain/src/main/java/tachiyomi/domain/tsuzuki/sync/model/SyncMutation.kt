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
    ) : SyncMutation

    @Serializable
    @SerialName("remove_field")
    data class RemoveField(
        override val recordId: String,
        val propertyPath: List<String>,
        override val recordUpdatedAtEpochMillis: Long,
    ) : SyncMutation

    @Serializable
    @SerialName("delete_record")
    data class DeleteRecord(
        override val recordId: String,
        override val recordUpdatedAtEpochMillis: Long,
        val deletedAtEpochMillis: Long,
    ) : SyncMutation
}

@Serializable
data class SyncMutationBatch(
    val revision: SyncRevision,
    val observed: SyncFrontier,
    val generatedAtEpochMillis: Long,
    val mutations: List<SyncMutation>,
)
