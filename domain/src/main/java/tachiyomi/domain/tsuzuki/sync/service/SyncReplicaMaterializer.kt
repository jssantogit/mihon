package tachiyomi.domain.tsuzuki.sync.service

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import tachiyomi.domain.tsuzuki.sync.model.SyncConflict
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictKind
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictValue
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncFailure
import tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason
import tachiyomi.domain.tsuzuki.sync.model.SyncFrontier
import tachiyomi.domain.tsuzuki.sync.model.SyncMutation
import tachiyomi.domain.tsuzuki.sync.model.SyncMutationBatch
import tachiyomi.domain.tsuzuki.sync.model.SyncRecordEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncReplicaJournal
import tachiyomi.domain.tsuzuki.sync.model.SyncReplicaMaterializationResult
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision

class SyncReplicaMaterializer {

    fun materialize(
        kind: SyncDocumentKind,
        schemaVersion: Int,
        journals: List<SyncReplicaJournal>,
        materializedAtEpochMillis: Long,
        visibleFrontier: SyncFrontier? = null,
    ): SyncReplicaMaterializationResult {
        if (schemaVersion < 1 || materializedAtEpochMillis < 0) {
            return failure(SyncFailureReason.MALFORMED_DOCUMENT)
        }
        validate(kind, schemaVersion, journals)?.let { return failure(it) }

        val genesis = journals.mapNotNull { it.genesis }.firstOrNull()?.document
        val allBatches = linkedMapOf<SyncRevision, SyncMutationBatch>()
        journals.sortedBy(SyncReplicaJournal::ownerDeviceId).forEach { journal ->
            journal.batches.forEach { batch ->
                val previous = allBatches[batch.revision]
                if (previous != null && previous != batch) {
                    return failure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT)
                }
                allBatches[batch.revision] = batch
            }
        }

        val availableRevisions = allBatches.keys.toSet()
        fun frontierReferencesPublishedHistory(frontier: SyncFrontier): Boolean =
            frontier.entries.all { (deviceId, sequence) ->
                SyncRevision(deviceId, sequence) in availableRevisions
            }

        if (allBatches.values.any { !frontierReferencesPublishedHistory(it.observed) }) {
            return failure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT)
        }
        if (visibleFrontier != null && !frontierReferencesPublishedHistory(visibleFrontier)) {
            return failure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT)
        }

        val selected = allBatches.values
            .filter { batch -> visibleFrontier?.observes(batch.revision) != false }
            .sortedWith(compareBy({ it.revision.deviceId }, { it.revision.sequence }))

        val outputFrontier = selected.fold(SyncFrontier()) { frontier, batch ->
            frontier.advance(batch.revision)
        }
        val eventsByRecord = mutableMapOf<String, MutableList<Event>>()
        selected.forEach { batch ->
            batch.mutations.forEach { mutation ->
                eventsByRecord.getOrPut(mutation.recordId, ::mutableListOf) +=
                    Event(batch.revision, batch.observed, mutation)
            }
        }

        val records = genesis?.records.orEmpty().toMutableMap()
        val conflicts = mutableListOf<SyncConflict>()
        val recordIds = (records.keys + eventsByRecord.keys).toSortedSet()
        recordIds.forEach { recordId ->
            val base = genesis?.records?.get(recordId)
            val events = eventsByRecord[recordId].orEmpty()
            val reduction = reduceRecord(kind, recordId, base, events)
            reduction.record?.let { records[recordId] = it } ?: records.remove(recordId)
            conflicts += reduction.conflicts
        }

        val documentRevision = selected.lastOrNull()?.revision
            ?: genesis?.revision
            ?: SyncRevision("materialized", 0)

        return SyncReplicaMaterializationResult.Success(
            document = SyncDocumentEnvelope(
                schemaVersion = schemaVersion,
                kind = kind,
                revision = documentRevision,
                generatedAtEpochMillis = materializedAtEpochMillis,
                records = records.toSortedMap(),
            ),
            frontier = outputFrontier,
            conflicts = conflicts.sortedWith(
                compareBy(
                    SyncConflict::recordId,
                    { it.propertyPath.joinToString("\u0000") },
                    { it.kind.name },
                    { it.remote.toString() },
                ),
            ),
        )
    }

    private fun validate(
        kind: SyncDocumentKind,
        schemaVersion: Int,
        journals: List<SyncReplicaJournal>,
    ): SyncFailureReason? {
        if (journals.any { it.kind != kind }) return SyncFailureReason.MALFORMED_REMOTE_DOCUMENT
        val genesisValues = journals.map { it.genesis }
        if (genesisValues.any { it != null } && genesisValues.any { it == null }) {
            return SyncFailureReason.MALFORMED_REMOTE_DOCUMENT
        }
        val nonNullGenesis = genesisValues.filterNotNull()
        if (nonNullGenesis.any {
                it.document.kind != kind || it.document.schemaVersion != schemaVersion
            }
        ) {
            return SyncFailureReason.MALFORMED_REMOTE_DOCUMENT
        }
        if (nonNullGenesis.isNotEmpty() && nonNullGenesis.any { it != nonNullGenesis.first() }) {
            return SyncFailureReason.MALFORMED_REMOTE_DOCUMENT
        }
        val owners = mutableSetOf<String>()
        journals.forEach { journal ->
            if (!owners.add(journal.ownerDeviceId)) {
                return SyncFailureReason.MALFORMED_REMOTE_DOCUMENT
            }
        }
        return null
    }

    private fun reduceRecord(
        kind: SyncDocumentKind,
        recordId: String,
        base: SyncRecordEnvelope?,
        events: List<Event>,
    ): RecordReduction {
        if (events.isEmpty()) return RecordReduction(base)

        val maximalDeletes = events
            .filter { it.mutation is SyncMutation.DeleteRecord }
            .filter { candidate -> events.none { other -> happensBefore(candidate, other) } }

        val activeEvents = events.filter { it.mutation !is SyncMutation.DeleteRecord }
        val maximalFieldsByPath = activeEvents
            .groupBy { it.mutation.mutationPath() }
            .mapValues { (_, values) ->
                values.filter { candidate ->
                    values.none { other -> happensBefore(candidate, other) }
                }
            }

        if (maximalDeletes.isNotEmpty()) {
            val concurrentFields = maximalFieldsByPath.values.flatten()
                .filter { field ->
                    maximalDeletes.any { deletion -> concurrent(deletion, field) }
                }
            if (concurrentFields.isNotEmpty()) {
                val conflicts = concurrentFields
                    .sortedWith(eventComparator)
                    .map { field ->
                        SyncConflict(
                            documentKind = kind,
                            recordId = recordId,
                            propertyPath = emptyList(),
                            kind = SyncConflictKind.DELETE_EDIT,
                            base = base.toConflictValue(),
                            local = SyncConflictValue.Tombstone,
                            remote = field.toRecordConflictValue(),
                        )
                    }
                return RecordReduction(record = base, conflicts = conflicts)
            }

            val delete = maximalDeletes.maxBy { it.mutation.recordUpdatedAtEpochMillis }
            val deleted = delete.mutation as SyncMutation.DeleteRecord
            return RecordReduction(
                SyncRecordEnvelope(
                    id = recordId,
                    revision = delete.revision,
                    updatedAtEpochMillis = deleted.recordUpdatedAtEpochMillis,
                    deletedAtEpochMillis = deleted.deletedAtEpochMillis,
                    fields = base?.fields ?: JsonObject(emptyMap()),
                ),
            )
        }

        var fields = base?.takeUnless(SyncRecordEnvelope::isTombstone)?.fields ?: JsonObject(emptyMap())
        val conflicts = mutableListOf<SyncConflict>()
        var revision = base?.revision ?: SyncRevision("materialized", 0)
        var updatedAt = base?.updatedAtEpochMillis ?: 0L

        maximalFieldsByPath.toSortedMap(pathComparator).forEach { (path, contenders) ->
            val distinct = contenders
                .groupBy { it.semanticValue() }
                .values
                .map { equivalent -> equivalent.minWith(eventComparator) }
                .sortedWith(eventComparator)

            if (distinct.size > 1) {
                val anchor = distinct.first()
                distinct.drop(1).forEach { contender ->
                    conflicts += SyncConflict(
                        documentKind = kind,
                        recordId = recordId,
                        propertyPath = path,
                        kind = SyncConflictKind.FIELD_DIVERGENCE,
                        base = base.fieldConflictValue(path),
                        local = anchor.toFieldConflictValue(),
                        remote = contender.toFieldConflictValue(),
                    )
                }
                return@forEach
            }

            val winner = distinct.singleOrNull() ?: return@forEach
            fields = applyField(fields, path, winner.mutation)
            revision = winner.revision
            updatedAt = maxOf(
                updatedAt,
                contenders.maxOf { it.mutation.recordUpdatedAtEpochMillis },
            )
        }

        return RecordReduction(
            record = SyncRecordEnvelope(
                id = recordId,
                revision = revision,
                updatedAtEpochMillis = updatedAt,
                fields = fields,
            ),
            conflicts = conflicts,
        )
    }

    private fun happensBefore(left: Event, right: Event): Boolean {
        if (left.revision == right.revision) return false
        if (left.revision.deviceId == right.revision.deviceId) {
            return left.revision.sequence < right.revision.sequence
        }
        return right.observed.observes(left.revision)
    }

    private fun concurrent(left: Event, right: Event): Boolean =
        !happensBefore(left, right) && !happensBefore(right, left)

    private fun applyField(
        fields: JsonObject,
        path: List<String>,
        mutation: SyncMutation,
    ): JsonObject {
        fun updateObject(current: JsonObject, depth: Int): JsonObject {
            val key = path[depth]
            val mutable = current.toMutableMap()
            if (depth == path.lastIndex) {
                when (mutation) {
                    is SyncMutation.SetField -> mutable[key] = mutation.value
                    is SyncMutation.RemoveField -> mutable.remove(key)
                    is SyncMutation.DeleteRecord -> Unit
                }
            } else {
                val child = current[key] as? JsonObject ?: JsonObject(emptyMap())
                mutable[key] = updateObject(child, depth + 1)
            }
            return JsonObject(mutable.toSortedMap())
        }
        return updateObject(fields, 0)
    }

    private fun Event.semanticValue(): String = when (val value = mutation) {
        is SyncMutation.SetField -> "set:" + value.value.toString()
        is SyncMutation.RemoveField -> "remove"
        is SyncMutation.DeleteRecord -> "delete"
    }

    private fun Event.toFieldConflictValue(): SyncConflictValue = when (val value = mutation) {
        is SyncMutation.SetField -> SyncConflictValue.Present(value.value)
        is SyncMutation.RemoveField -> SyncConflictValue.Missing
        is SyncMutation.DeleteRecord -> SyncConflictValue.Tombstone
    }

    private fun Event.toRecordConflictValue(): SyncConflictValue = when (val value = mutation) {
        is SyncMutation.SetField -> SyncConflictValue.Present(
            JsonObject(mapOf(value.propertyPath.joinToString(".") to value.value)),
        )
        is SyncMutation.RemoveField -> SyncConflictValue.Missing
        is SyncMutation.DeleteRecord -> SyncConflictValue.Tombstone
    }

    private fun SyncRecordEnvelope?.toConflictValue(): SyncConflictValue {
        if (this == null) return SyncConflictValue.Missing
        return if (isTombstone) SyncConflictValue.Tombstone else SyncConflictValue.Present(fields)
    }

    private fun SyncRecordEnvelope?.fieldConflictValue(path: List<String>): SyncConflictValue {
        if (this == null || isTombstone) return SyncConflictValue.Missing
        var current: JsonElement = fields
        path.forEach { key ->
            current = (current as? JsonObject)?.get(key) ?: return SyncConflictValue.Missing
        }
        return SyncConflictValue.Present(current)
    }

    private fun failure(reason: SyncFailureReason) =
        SyncReplicaMaterializationResult.Failure(SyncFailure(reason))

    private data class Event(
        val revision: SyncRevision,
        val observed: SyncFrontier,
        val mutation: SyncMutation,
    )

    private data class RecordReduction(
        val record: SyncRecordEnvelope?,
        val conflicts: List<SyncConflict> = emptyList(),
    )

    private companion object {
        val eventComparator = compareBy<Event>(
            { it.revision.deviceId },
            { it.revision.sequence },
            { it.mutation.recordId },
            { it.mutation.mutationPath().joinToString("\u0000") },
        )
        val pathComparator = Comparator<List<String>> { left, right ->
            left.joinToString("\u0000").compareTo(right.joinToString("\u0000"))
        }
    }
}

private fun SyncMutation.mutationPath(): List<String> = when (this) {
    is SyncMutation.SetField -> propertyPath
    is SyncMutation.RemoveField -> propertyPath
    is SyncMutation.DeleteRecord -> emptyList()
}
