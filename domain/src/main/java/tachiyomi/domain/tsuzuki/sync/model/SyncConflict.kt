package tachiyomi.domain.tsuzuki.sync.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SyncConflict(
    val remoteConflictId: Long? = null,
    val documentKind: SyncDocumentKind,
    val recordId: String,
    val propertyPath: List<String>,
    val kind: SyncConflictKind,
    val base: SyncConflictValue,
    val local: SyncConflictValue,
    val remote: SyncConflictValue,
) {
    init {
        require(remoteConflictId == null || remoteConflictId > 0) {
            "Remote sync conflict ID must be positive"
        }
        require(recordId.isNotBlank()) { "Sync conflict record ID must not be blank" }
        require(propertyPath.none(String::isBlank)) {
            "Sync conflict property path segments must not be blank"
        }
    }
}

@Serializable
enum class SyncConflictKind {
    FIELD_DIVERGENCE,
    DELETE_EDIT,
}

@Serializable
sealed interface SyncConflictValue {

    @Serializable
    @SerialName("missing")
    data object Missing : SyncConflictValue

    @Serializable
    @SerialName("tombstone")
    data object Tombstone : SyncConflictValue

    @Serializable
    @SerialName("present")
    data class Present(
        val value: JsonElement,
    ) : SyncConflictValue
}
