package tachiyomi.domain.tsuzuki.sync.service

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import tachiyomi.domain.tsuzuki.sync.model.SyncConflict
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictKind
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictValue
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncFailure
import tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason
import tachiyomi.domain.tsuzuki.sync.model.SyncMergeOutcome
import tachiyomi.domain.tsuzuki.sync.model.SyncMergeResult
import tachiyomi.domain.tsuzuki.sync.model.SyncRecordEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision

class ThreeWaySyncMerger {

    fun merge(
        base: SyncDocumentEnvelope?,
        local: SyncDocumentEnvelope,
        remote: SyncDocumentEnvelope,
        mergeRevision: SyncRevision,
        mergedAtEpochMillis: Long,
    ): SyncMergeResult {
        validateDocuments(base, local, remote)?.let {
            return SyncMergeResult.Rejected(it)
        }

        val baseRecords = base?.records.orEmpty()
        if (baseRecords.keys.any { it !in local.records || it !in remote.records }) {
            return SyncMergeResult.Rejected(
                SyncFailure(SyncFailureReason.MALFORMED_DOCUMENT),
            )
        }

        val mergedRecords = linkedMapOf<String, SyncRecordEnvelope>()
        val conflicts = mutableListOf<SyncConflict>()
        val recordIds = (baseRecords.keys + local.records.keys + remote.records.keys)
            .toSortedSet()

        for (recordId in recordIds) {
            val result = mergeRecord(
                documentKind = local.kind,
                recordId = recordId,
                base = baseRecords[recordId],
                local = local.records[recordId],
                remote = remote.records[recordId],
                mergeRevision = mergeRevision,
                mergedAtEpochMillis = mergedAtEpochMillis,
            )
            result.record?.let { mergedRecords[recordId] = it }
            conflicts += result.conflicts
        }

        val requiresLocalApply = !sameRecordMaps(mergedRecords, local.records)
        val requiresRemoteWrite = conflicts.isEmpty() &&
            !sameRecordMaps(mergedRecords, remote.records)

        return SyncMergeResult.Success(
            SyncMergeOutcome(
                documentKind = local.kind,
                schemaVersion = local.schemaVersion,
                records = mergedRecords,
                conflicts = conflicts,
                requiresLocalApply = requiresLocalApply,
                requiresRemoteWrite = requiresRemoteWrite,
            ),
        )
    }

    private fun validateDocuments(
        base: SyncDocumentEnvelope?,
        local: SyncDocumentEnvelope,
        remote: SyncDocumentEnvelope,
    ): SyncFailure? {
        if (local.kind != remote.kind || base?.kind?.let { it != local.kind } == true) {
            return SyncFailure(SyncFailureReason.MALFORMED_DOCUMENT)
        }

        if (local.schemaVersion != remote.schemaVersion ||
            base?.schemaVersion?.let { it != local.schemaVersion } == true
        ) {
            return SyncFailure(SyncFailureReason.UNSUPPORTED_SCHEMA)
        }

        return null
    }

    private fun mergeRecord(
        documentKind: tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind,
        recordId: String,
        base: SyncRecordEnvelope?,
        local: SyncRecordEnvelope?,
        remote: SyncRecordEnvelope?,
        mergeRevision: SyncRevision,
        mergedAtEpochMillis: Long,
    ): RecordMerge {
        if (base == null) {
            return mergeNewRecord(
                documentKind = documentKind,
                recordId = recordId,
                local = local,
                remote = remote,
                mergeRevision = mergeRevision,
                mergedAtEpochMillis = mergedAtEpochMillis,
            )
        }

        check(local != null && remote != null)

        if (sameRecordContent(local, remote)) {
            return RecordMerge(preferEquivalent(local, remote))
        }
        if (sameRecordContent(local, base)) {
            return RecordMerge(remote)
        }
        if (sameRecordContent(remote, base)) {
            return RecordMerge(local)
        }

        if (local.isTombstone || remote.isTombstone) {
            return RecordMerge(
                record = local,
                conflicts = listOf(
                    SyncConflict(
                        documentKind = documentKind,
                        recordId = recordId,
                        propertyPath = emptyList(),
                        kind = SyncConflictKind.DELETE_EDIT,
                        base = base.toConflictValue(),
                        local = local.toConflictValue(),
                        remote = remote.toConflictValue(),
                    ),
                ),
            )
        }

        val baseFields = base.fields.takeUnless { base.isTombstone }
        return mergeActiveRecord(
            documentKind = documentKind,
            recordId = recordId,
            baseFields = baseFields,
            local = local,
            remote = remote,
            mergeRevision = mergeRevision,
            mergedAtEpochMillis = mergedAtEpochMillis,
        )
    }

    private fun mergeNewRecord(
        documentKind: tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind,
        recordId: String,
        local: SyncRecordEnvelope?,
        remote: SyncRecordEnvelope?,
        mergeRevision: SyncRevision,
        mergedAtEpochMillis: Long,
    ): RecordMerge {
        if (local == null) return RecordMerge(remote)
        if (remote == null) return RecordMerge(local)

        if (sameRecordContent(local, remote)) {
            return RecordMerge(preferEquivalent(local, remote))
        }

        if (local.isTombstone || remote.isTombstone) {
            return RecordMerge(
                record = local,
                conflicts = listOf(
                    SyncConflict(
                        documentKind = documentKind,
                        recordId = recordId,
                        propertyPath = emptyList(),
                        kind = SyncConflictKind.DELETE_EDIT,
                        base = SyncConflictValue.Missing,
                        local = local.toConflictValue(),
                        remote = remote.toConflictValue(),
                    ),
                ),
            )
        }

        return mergeActiveRecord(
            documentKind = documentKind,
            recordId = recordId,
            baseFields = null,
            local = local,
            remote = remote,
            mergeRevision = mergeRevision,
            mergedAtEpochMillis = mergedAtEpochMillis,
        )
    }

    private fun mergeActiveRecord(
        documentKind: tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind,
        recordId: String,
        baseFields: JsonObject?,
        local: SyncRecordEnvelope,
        remote: SyncRecordEnvelope,
        mergeRevision: SyncRevision,
        mergedAtEpochMillis: Long,
    ): RecordMerge {
        val fieldMerge = mergeObject(
            documentKind = documentKind,
            recordId = recordId,
            base = baseFields,
            local = local.fields,
            remote = remote.fields,
            path = emptyList(),
        )

        val record = when {
            fieldMerge.value == local.fields -> local
            fieldMerge.value == remote.fields -> remote
            else -> SyncRecordEnvelope(
                id = recordId,
                revision = mergeRevision,
                updatedAtEpochMillis = mergedAtEpochMillis,
                fields = fieldMerge.value,
            )
        }

        return RecordMerge(
            record = record,
            conflicts = fieldMerge.conflicts,
        )
    }

    private fun mergeObject(
        documentKind: tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind,
        recordId: String,
        base: JsonObject?,
        local: JsonObject,
        remote: JsonObject,
        path: List<String>,
    ): ObjectMerge {
        val keys = (base?.keys.orEmpty() + local.keys + remote.keys).toSortedSet()
        val merged = linkedMapOf<String, JsonElement>()
        val conflicts = mutableListOf<SyncConflict>()

        for (key in keys) {
            val valueMerge = mergeValue(
                documentKind = documentKind,
                recordId = recordId,
                base = base.presence(key),
                local = local.presence(key),
                remote = remote.presence(key),
                path = path + key,
            )
            when (val value = valueMerge.value) {
                FieldValue.Missing -> Unit
                is FieldValue.Present -> merged[key] = value.value
            }
            conflicts += valueMerge.conflicts
        }

        return ObjectMerge(
            value = JsonObject(merged),
            conflicts = conflicts,
        )
    }

    private fun mergeValue(
        documentKind: tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind,
        recordId: String,
        base: FieldValue,
        local: FieldValue,
        remote: FieldValue,
        path: List<String>,
    ): ValueMerge {
        if (local == remote) return ValueMerge(local)
        if (local == base) return ValueMerge(remote)
        if (remote == base) return ValueMerge(local)

        val localObject = (local as? FieldValue.Present)?.value as? JsonObject
        val remoteObject = (remote as? FieldValue.Present)?.value as? JsonObject
        val baseObject = when (base) {
            FieldValue.Missing -> null
            is FieldValue.Present -> base.value as? JsonObject ?: return conflictValue(
                documentKind,
                recordId,
                path,
                base,
                local,
                remote,
            )
        }

        if (localObject != null && remoteObject != null) {
            val nested = mergeObject(
                documentKind = documentKind,
                recordId = recordId,
                base = baseObject,
                local = localObject,
                remote = remoteObject,
                path = path,
            )
            return ValueMerge(
                value = FieldValue.Present(nested.value),
                conflicts = nested.conflicts,
            )
        }

        return conflictValue(
            documentKind = documentKind,
            recordId = recordId,
            path = path,
            base = base,
            local = local,
            remote = remote,
        )
    }

    private fun conflictValue(
        documentKind: tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind,
        recordId: String,
        path: List<String>,
        base: FieldValue,
        local: FieldValue,
        remote: FieldValue,
    ): ValueMerge {
        return ValueMerge(
            value = local,
            conflicts = listOf(
                SyncConflict(
                    documentKind = documentKind,
                    recordId = recordId,
                    propertyPath = path,
                    kind = SyncConflictKind.FIELD_DIVERGENCE,
                    base = base.toConflictValue(),
                    local = local.toConflictValue(),
                    remote = remote.toConflictValue(),
                ),
            ),
        )
    }

    private fun JsonObject?.presence(key: String): FieldValue {
        if (this == null || key !in this) return FieldValue.Missing
        return FieldValue.Present(getValue(key))
    }

    private fun SyncRecordEnvelope.toConflictValue(): SyncConflictValue {
        return if (isTombstone) {
            SyncConflictValue.Tombstone
        } else {
            SyncConflictValue.Present(fields)
        }
    }

    private fun FieldValue.toConflictValue(): SyncConflictValue {
        return when (this) {
            FieldValue.Missing -> SyncConflictValue.Missing
            is FieldValue.Present -> SyncConflictValue.Present(value)
        }
    }

    private fun sameRecordMaps(
        left: Map<String, SyncRecordEnvelope>,
        right: Map<String, SyncRecordEnvelope>,
    ): Boolean {
        if (left.keys != right.keys) return false
        return left.all { (id, record) ->
            sameRecordContent(record, right.getValue(id))
        }
    }

    private fun sameRecordContent(
        left: SyncRecordEnvelope,
        right: SyncRecordEnvelope,
    ): Boolean {
        if (left.isTombstone || right.isTombstone) {
            return left.isTombstone && right.isTombstone
        }
        return left.fields == right.fields
    }

    private fun preferEquivalent(
        left: SyncRecordEnvelope,
        right: SyncRecordEnvelope,
    ): SyncRecordEnvelope {
        val sequenceComparison = left.revision.sequence.compareTo(right.revision.sequence)
        if (sequenceComparison != 0) {
            return if (sequenceComparison > 0) left else right
        }

        val deviceComparison = left.revision.deviceId.compareTo(right.revision.deviceId)
        if (deviceComparison != 0) {
            return if (deviceComparison > 0) left else right
        }

        return if (left.updatedAtEpochMillis >= right.updatedAtEpochMillis) left else right
    }

    private data class RecordMerge(
        val record: SyncRecordEnvelope?,
        val conflicts: List<SyncConflict> = emptyList(),
    )

    private data class ObjectMerge(
        val value: JsonObject,
        val conflicts: List<SyncConflict>,
    )

    private data class ValueMerge(
        val value: FieldValue,
        val conflicts: List<SyncConflict> = emptyList(),
    )

    private sealed interface FieldValue {
        data object Missing : FieldValue

        data class Present(
            val value: JsonElement,
        ) : FieldValue
    }
}
