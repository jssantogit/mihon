package tachiyomi.domain.tsuzuki.sync.service

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncMutation
import tachiyomi.domain.tsuzuki.sync.model.SyncRecordEnvelope

class SyncDocumentDiffer {

    fun diff(
        base: SyncDocumentEnvelope?,
        current: SyncDocumentEnvelope,
    ): List<SyncMutation> {
        require(base == null || base.kind == current.kind) {
            "Sync diff documents must have matching kinds"
        }
        require(base == null || base.schemaVersion == current.schemaVersion) {
            "Sync diff documents must have matching schemas"
        }

        val mutations = mutableListOf<SyncMutation>()
        val recordIds = (base?.records?.keys.orEmpty() + current.records.keys).toSortedSet()
        recordIds.forEach { recordId ->
            val before = base?.records?.get(recordId)
            val after = current.records[recordId]
            when {
                before == null && after != null -> emitCreated(after, mutations)
                before != null && after == null -> {
                    val deletedAt = maxOf(
                        before.deletedAtEpochMillis ?: before.updatedAtEpochMillis,
                        current.generatedAtEpochMillis,
                    )
                    mutations += SyncMutation.DeleteRecord(
                        recordId = recordId,
                        recordUpdatedAtEpochMillis = deletedAt,
                        deletedAtEpochMillis = deletedAt,
                    )
                }
                before != null && after != null -> diffRecord(before, after, mutations)
            }
        }
        return mutations
    }

    private fun emitCreated(
        record: SyncRecordEnvelope,
        mutations: MutableList<SyncMutation>,
    ) {
        if (record.isTombstone) {
            mutations += SyncMutation.DeleteRecord(
                recordId = record.id,
                recordUpdatedAtEpochMillis = record.updatedAtEpochMillis,
                deletedAtEpochMillis = checkNotNull(record.deletedAtEpochMillis),
            )
            return
        }
        emitObjectDiff(
            recordId = record.id,
            before = null,
            after = record.fields,
            path = emptyList(),
            updatedAt = record.updatedAtEpochMillis,
            mutations = mutations,
        )
    }

    private fun diffRecord(
        before: SyncRecordEnvelope,
        after: SyncRecordEnvelope,
        mutations: MutableList<SyncMutation>,
    ) {
        if (before.isTombstone && after.isTombstone) return
        if (!before.isTombstone && after.isTombstone) {
            mutations += SyncMutation.DeleteRecord(
                recordId = after.id,
                recordUpdatedAtEpochMillis = after.updatedAtEpochMillis,
                deletedAtEpochMillis = checkNotNull(after.deletedAtEpochMillis),
            )
            return
        }

        emitObjectDiff(
            recordId = after.id,
            before = if (before.isTombstone) null else before.fields,
            after = after.fields,
            path = emptyList(),
            updatedAt = after.updatedAtEpochMillis,
            mutations = mutations,
        )
    }

    private fun emitObjectDiff(
        recordId: String,
        before: JsonObject?,
        after: JsonObject,
        path: List<String>,
        updatedAt: Long,
        mutations: MutableList<SyncMutation>,
    ) {
        val keys = (before?.keys.orEmpty() + after.keys).toSortedSet()
        keys.forEach { key ->
            val nextPath = path + key
            val old = before?.get(key)
            val new = after[key]
            when {
                new == null -> mutations += SyncMutation.RemoveField(
                    recordId = recordId,
                    propertyPath = nextPath,
                    recordUpdatedAtEpochMillis = updatedAt,
                )
                old == null -> emitAddedValue(recordId, nextPath, new, updatedAt, mutations)
                old == new -> Unit
                old is JsonObject && new is JsonObject -> emitObjectDiff(
                    recordId,
                    old,
                    new,
                    nextPath,
                    updatedAt,
                    mutations,
                )
                else -> mutations += SyncMutation.SetField(
                    recordId = recordId,
                    propertyPath = nextPath,
                    value = new,
                    recordUpdatedAtEpochMillis = updatedAt,
                )
            }
        }
    }

    private fun emitAddedValue(
        recordId: String,
        path: List<String>,
        value: JsonElement,
        updatedAt: Long,
        mutations: MutableList<SyncMutation>,
    ) {
        if (value is JsonObject) {
            emitObjectDiff(recordId, null, value, path, updatedAt, mutations)
        } else {
            mutations += SyncMutation.SetField(
                recordId = recordId,
                propertyPath = path,
                value = value,
                recordUpdatedAtEpochMillis = updatedAt,
            )
        }
    }
}
