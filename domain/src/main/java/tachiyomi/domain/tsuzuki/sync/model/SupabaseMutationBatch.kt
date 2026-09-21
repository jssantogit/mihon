package tachiyomi.domain.tsuzuki.sync.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class SupabaseMutationBatch(
    val mutationId: String,
    val originClientId: String,
    val domain: String,
    val baseCursor: Long,
    val operations: List<SyncMutation>,
) {
    init {
        require(mutationId.isNotBlank()) { "Supabase mutation ID must not be blank" }
        require(originClientId.isNotBlank()) { "Supabase origin client ID must not be blank" }
        require(domain.isNotBlank()) { "Supabase sync domain must not be blank" }
        require(baseCursor >= 0) { "Supabase base cursor must not be negative" }
        require(operations.isNotEmpty()) { "Supabase mutation batch must contain operations" }
    }

    val documentKind: SyncDocumentKind
        get() = SyncDocumentKind.valueOf(domain)
}

data class SupabaseSyncCursor(
    val documentKind: SyncDocumentKind,
    val eventCursor: Long,
    val lastSuccessfulSyncAtEpochMillis: Long?,
) {
    init {
        require(eventCursor >= 0) { "Supabase event cursor must not be negative" }
        require(
            lastSuccessfulSyncAtEpochMillis == null ||
                lastSuccessfulSyncAtEpochMillis >= 0,
        ) {
            "Supabase last successful sync time must not be negative"
        }
    }
}

data class SupabasePendingMutation(
    val batch: SupabaseMutationBatch,
    val createdAtEpochMillis: Long,
    val attemptCount: Int = 0,
    val nextAttemptAtEpochMillis: Long? = null,
) {
    init {
        require(createdAtEpochMillis >= 0) {
            "Supabase pending mutation creation time must not be negative"
        }
        require(attemptCount >= 0) {
            "Supabase pending mutation attempt count must not be negative"
        }
        require(nextAttemptAtEpochMillis == null || nextAttemptAtEpochMillis >= 0) {
            "Supabase pending mutation retry time must not be negative"
        }
    }
}

@Serializable
enum class SupabaseSyncOperation {
    @SerialName("set")
    SET,

    @SerialName("remove")
    REMOVE,

    @SerialName("delete")
    DELETE,
}

@Serializable
data class SupabaseSyncEvent(
    val eventId: Long,
    val recordId: String,
    val fieldPath: String?,
    val operation: SupabaseSyncOperation,
    val value: JsonElement? = null,
) {
    init {
        require(eventId > 0) { "Supabase event ID must be positive" }
        require(recordId.isNotBlank()) { "Supabase event record ID must not be blank" }
        require(
            (operation == SupabaseSyncOperation.DELETE && fieldPath == null) ||
                (operation != SupabaseSyncOperation.DELETE && !fieldPath.isNullOrBlank()),
        ) {
            "Supabase event field path does not match its operation"
        }
    }
}

@Serializable
data class SupabaseSyncSnapshotRecord(
    val recordId: String,
    val isDeleted: Boolean,
    val fields: JsonObject,
)

@Serializable
data class SupabaseSyncSnapshot(
    val documentKind: SyncDocumentKind,
    val cursor: Long,
    val records: List<SupabaseSyncSnapshotRecord>,
) {
    init {
        require(cursor >= 0) { "Supabase snapshot cursor must not be negative" }
    }
}

data class SupabaseRemoteConflict(
    val conflictId: Long,
    val recordId: String,
    val fieldPath: String?,
    val kind: SyncConflictKind,
    val localValue: JsonElement?,
    val remoteValue: JsonElement?,
)

data class SupabasePushResult(
    val cursor: Long,
    val conflicts: List<SupabaseRemoteConflict>,
) {
    init {
        require(cursor >= 0) { "Supabase push cursor must not be negative" }
    }
}

object SupabaseFieldPathCodec {
    fun encode(path: List<String>): String {
        require(path.isNotEmpty() && path.none(String::isBlank)) {
            "Sync field path must contain nonblank segments"
        }
        return path.joinToString("/") { segment ->
            segment
                .replace("~", "~0")
                .replace("/", "~1")
        }
    }

    fun decode(path: String): List<String> {
        require(path.isNotBlank()) { "Encoded sync field path must not be blank" }
        return path.split("/").map { segment ->
            buildString {
                var index = 0
                while (index < segment.length) {
                    if (segment[index] == '~' && index + 1 < segment.length) {
                        when (segment[index + 1]) {
                            '0' -> {
                                append('~')
                                index += 2
                            }
                            '1' -> {
                                append('/')
                                index += 2
                            }
                            else -> {
                                append('~')
                                index += 1
                            }
                        }
                    } else {
                        append(segment[index])
                        index += 1
                    }
                }
            }
        }
    }
}
