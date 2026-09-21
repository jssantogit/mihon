package tachiyomi.domain.tsuzuki.sync.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import tachiyomi.domain.tsuzuki.account.model.AccountState
import tachiyomi.domain.tsuzuki.account.repository.AccountRepository
import tachiyomi.domain.tsuzuki.sync.model.SupabaseFieldPathCodec
import tachiyomi.domain.tsuzuki.sync.model.SupabaseMutationBatch
import tachiyomi.domain.tsuzuki.sync.model.SupabasePendingMutation
import tachiyomi.domain.tsuzuki.sync.model.SupabaseRemoteConflict
import tachiyomi.domain.tsuzuki.sync.model.SupabaseSyncSnapshot
import tachiyomi.domain.tsuzuki.sync.model.SyncConflict
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictKind
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictResolutionChoice
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictResolutionResult
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictValue
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncFailure
import tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason
import tachiyomi.domain.tsuzuki.sync.model.SyncFrontier
import tachiyomi.domain.tsuzuki.sync.model.SyncMutation
import tachiyomi.domain.tsuzuki.sync.model.SyncRecordEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision
import tachiyomi.domain.tsuzuki.sync.model.SyncStoredState
import tachiyomi.domain.tsuzuki.sync.model.SyncTransportResult
import tachiyomi.domain.tsuzuki.sync.repository.SyncConflictRepository
import tachiyomi.domain.tsuzuki.sync.repository.SyncOutboxRepository
import tachiyomi.domain.tsuzuki.sync.repository.SyncStateRepository
import java.util.UUID

class SupabaseConflictResolver(
    private val accountRepository: AccountRepository,
    private val transport: SupabaseSyncTransport,
    private val outboxRepository: SyncOutboxRepository,
    private val stateRepository: SyncStateRepository,
    private val supabaseStateStore: SupabaseSyncStateStore,
    private val conflictRepository: SyncConflictRepository,
    adapters: List<SyncDocumentAdapter>,
    private val clientIdentityProvider: SyncClientIdentityProvider,
    private val clock: SyncClock,
    private val differ: SyncDocumentDiffer = SyncDocumentDiffer(),
    private val mutationIdSource: () -> String = { UUID.randomUUID().toString() },
) {

    private val mutex = Mutex()
    private val adapters = adapters.associateBy(SyncDocumentAdapter::documentKind)

    suspend fun resolve(
        conflict: SyncConflict,
        choice: SyncConflictResolutionChoice,
    ): SyncConflictResolutionResult = mutex.withLock {
        if (accountRepository.state.value !is AccountState.Authenticated) {
            return@withLock failed(SyncFailureReason.AUTHORIZATION_REQUIRED)
        }

        val adapter = adapters[conflict.documentKind]
            ?: return@withLock failed(SyncFailureReason.LOCAL_STATE_UNAVAILABLE)

        try {
            when (choice) {
                SyncConflictResolutionChoice.KEEP_REMOTE -> {
                    keepRemote(adapter, conflict)
                }
                SyncConflictResolutionChoice.KEEP_LOCAL -> {
                    keepLocal(adapter, conflict)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            failed(SyncFailureReason.LOCAL_STATE_UNAVAILABLE)
        }
    }

    private suspend fun keepRemote(
        adapter: SyncDocumentAdapter,
        conflict: SyncConflict,
    ): SyncConflictResolutionResult {
        val now = clock.nowEpochMillis()
        val local = adapter.exportDocument()
        val snapshot = when (val result = transport.snapshot(conflict.documentKind)) {
            is SyncTransportResult.Failure -> return SyncConflictResolutionResult.Failed(result.failure)
            is SyncTransportResult.Success -> result.value
        }
        val remote = snapshot.toDocument(
            schemaVersion = local.schemaVersion,
            now = now,
        )
        persistRemote(snapshot, remote)

        val resolvedLocal = applyRemoteChoice(
            local = local,
            remote = remote,
            conflict = conflict,
            now = now,
        )
        adapter.applyDocument(resolvedLocal)

        reconcileOutbox(
            adapter = adapter,
            remote = remote,
            now = now,
        )

        val remoteConflictId = conflict.remoteConflictId
        if (remoteConflictId != null) {
            when (val acknowledged = transport.ackConflict(remoteConflictId)) {
                is SyncTransportResult.Failure -> {
                    return SyncConflictResolutionResult.Failed(acknowledged.failure)
                }
                is SyncTransportResult.Success -> {
                    if (!acknowledged.value) {
                        return failed(SyncFailureReason.REMOTE_CHANGED)
                    }
                }
            }
        }

        removeConflict(conflict, now)
        return SyncConflictResolutionResult.Resolved
    }

    private suspend fun keepLocal(
        adapter: SyncDocumentAdapter,
        conflict: SyncConflict,
    ): SyncConflictResolutionResult {
        val now = clock.nowEpochMillis()
        val local = adapter.exportDocument()
        val snapshot = when (val result = transport.snapshot(conflict.documentKind)) {
            is SyncTransportResult.Failure -> return SyncConflictResolutionResult.Failed(result.failure)
            is SyncTransportResult.Success -> result.value
        }
        val remote = snapshot.toDocument(
            schemaVersion = local.schemaVersion,
            now = now,
        )
        persistRemote(snapshot, remote)

        val existingPending = supabaseStateStore.getPending(conflict.documentKind)
        val pending = existingPending ?: SupabasePendingMutation(
            batch = SupabaseMutationBatch(
                mutationId = mutationIdSource(),
                originClientId = clientIdentityProvider.getOrCreate(),
                domain = conflict.documentKind.name,
                baseCursor = snapshot.cursor,
                operations = listOf(localOperation(local, conflict, now)),
            ),
            createdAtEpochMillis = now,
        ).also {
            supabaseStateStore.putPending(it)
        }

        val pushed = when (val result = transport.push(pending.batch)) {
            is SyncTransportResult.Failure -> {
                supabaseStateStore.recordPendingFailure(
                    mutation = pending,
                    nextAttemptAtEpochMillis = null,
                )
                return SyncConflictResolutionResult.Failed(result.failure)
            }
            is SyncTransportResult.Success -> result.value
        }

        if (pushed.conflicts.isNotEmpty()) {
            supabaseStateStore.deletePending(pending.batch.mutationId)
            val newConflicts = pushed.conflicts.map {
                it.toSyncConflict(conflict.documentKind)
            }
            replaceWithConflicts(
                original = conflict,
                replacements = newConflicts,
                now = now,
            )
            return SyncConflictResolutionResult.StillConflicted(
                conflictCount = newConflicts.size,
            )
        }

        val remoteConflictId = conflict.remoteConflictId
        if (remoteConflictId != null) {
            when (val acknowledged = transport.ackConflict(remoteConflictId)) {
                is SyncTransportResult.Failure -> {
                    // Keep the applied batch persisted so a retry replays the exact same mutation ID.
                    return SyncConflictResolutionResult.Failed(acknowledged.failure)
                }
                is SyncTransportResult.Success -> {
                    if (!acknowledged.value) {
                        return failed(SyncFailureReason.REMOTE_CHANGED)
                    }
                }
            }
        }

        supabaseStateStore.deletePending(pending.batch.mutationId)
        removeConflict(conflict, now)

        // The accepted local mutation may now be ahead of the pre-push snapshot.
        // Keep the outbox dirty until the normal sync cycle observes its emitted event.
        outboxRepository.markDirty(
            documentKind = conflict.documentKind,
            enqueuedAtEpochMillis = now,
        )

        return SyncConflictResolutionResult.Resolved
    }

    private suspend fun persistRemote(
        snapshot: SupabaseSyncSnapshot,
        remote: SyncDocumentEnvelope,
    ) {
        val existing = stateRepository.get(snapshot.documentKind)
        val cursor = supabaseStateStore.getCursor(snapshot.documentKind)
        stateRepository.put(
            SyncStoredState(
                documentKind = snapshot.documentKind,
                acceptedBase = remote,
                remoteRevision = null,
                lastSuccessfulSyncAtEpochMillis = cursor?.lastSuccessfulSyncAtEpochMillis,
                acceptedFrontier = existing?.acceptedFrontier ?: SyncFrontier(),
            ),
        )
        supabaseStateStore.putCursor(
            documentKind = snapshot.documentKind,
            eventCursor = snapshot.cursor,
            lastSuccessfulSyncAtEpochMillis = cursor?.lastSuccessfulSyncAtEpochMillis,
        )
    }

    private suspend fun reconcileOutbox(
        adapter: SyncDocumentAdapter,
        remote: SyncDocumentEnvelope,
        now: Long,
    ) {
        val local = adapter.exportDocument()
        val remaining = differ.diff(remote, local)
        if (remaining.isEmpty() && !adapter.hasUnportableLocalState()) {
            outboxRepository.clear(adapter.documentKind)
        } else {
            outboxRepository.markDirty(adapter.documentKind, now)
        }
    }

    private suspend fun removeConflict(
        conflict: SyncConflict,
        now: Long,
    ) {
        val remaining = conflictRepository
            .getForDocument(conflict.documentKind)
            .map { it.conflict }
            .filterNot { stored -> stored.matches(conflict) }

        if (remaining.isEmpty()) {
            conflictRepository.clearForDocument(conflict.documentKind)
        } else {
            conflictRepository.replaceForDocument(
                documentKind = conflict.documentKind,
                conflicts = remaining,
                createdAtEpochMillis = now,
            )
        }
    }

    private suspend fun replaceWithConflicts(
        original: SyncConflict,
        replacements: List<SyncConflict>,
        now: Long,
    ) {
        val retained = conflictRepository
            .getForDocument(original.documentKind)
            .map { it.conflict }
            .filterNot { it.matches(original) }

        conflictRepository.replaceForDocument(
            documentKind = original.documentKind,
            conflicts = (retained + replacements).distinctBy { conflict ->
                listOf(
                    conflict.remoteConflictId?.toString().orEmpty(),
                    conflict.recordId,
                    conflict.propertyPath.joinToString("/"),
                    conflict.kind.name,
                ).joinToString("|")
            },
            createdAtEpochMillis = now,
        )
    }

    private fun SyncConflict.matches(other: SyncConflict): Boolean {
        if (remoteConflictId != null && other.remoteConflictId != null) {
            return remoteConflictId == other.remoteConflictId
        }
        return documentKind == other.documentKind &&
            recordId == other.recordId &&
            propertyPath == other.propertyPath &&
            kind == other.kind
    }

    private fun applyRemoteChoice(
        local: SyncDocumentEnvelope,
        remote: SyncDocumentEnvelope,
        conflict: SyncConflict,
        now: Long,
    ): SyncDocumentEnvelope {
        val records = local.records.toMutableMap()
        val localRecord = records[conflict.recordId]
        val remoteRecord = remote.records[conflict.recordId]
        val revision = SyncRevision("conflict-remote", now)

        if (
            conflict.kind == SyncConflictKind.DELETE_EDIT &&
            conflict.propertyPath.isEmpty()
        ) {
            if (remoteRecord == null || remoteRecord.isTombstone) {
                val base = localRecord ?: remoteRecord ?: SyncRecordEnvelope(
                    id = conflict.recordId,
                    revision = revision,
                    updatedAtEpochMillis = now,
                    fields = JsonObject(emptyMap()),
                )
                records[conflict.recordId] = base.copy(
                    revision = revision,
                    updatedAtEpochMillis = now,
                    deletedAtEpochMillis = now,
                )
            } else {
                records[conflict.recordId] = remoteRecord.copy(
                    revision = revision,
                    updatedAtEpochMillis = now,
                )
            }
        } else if (remoteRecord == null || remoteRecord.isTombstone) {
            val base = localRecord ?: remoteRecord ?: SyncRecordEnvelope(
                id = conflict.recordId,
                revision = revision,
                updatedAtEpochMillis = now,
                fields = JsonObject(emptyMap()),
            )
            records[conflict.recordId] = base.copy(
                revision = revision,
                updatedAtEpochMillis = now,
                deletedAtEpochMillis = now,
            )
        } else {
            val base = localRecord ?: remoteRecord
            val lookup = lookupPath(remoteRecord.fields, conflict.propertyPath)
            val fields = if (lookup.present) {
                setPath(
                    root = base.fields,
                    path = conflict.propertyPath,
                    value = checkNotNull(lookup.value),
                )
            } else {
                removePath(
                    root = base.fields,
                    path = conflict.propertyPath,
                )
            }
            records[conflict.recordId] = base.copy(
                revision = revision,
                updatedAtEpochMillis = now,
                deletedAtEpochMillis = null,
                fields = fields,
            )
        }

        return local.copy(
            revision = revision,
            generatedAtEpochMillis = now,
            records = records,
        )
    }

    private fun localOperation(
        local: SyncDocumentEnvelope,
        conflict: SyncConflict,
        now: Long,
    ): SyncMutation {
        val record = local.records[conflict.recordId]
        if (
            record == null ||
            record.isTombstone ||
            (
                conflict.kind == SyncConflictKind.DELETE_EDIT &&
                    conflict.propertyPath.isEmpty() &&
                    conflict.local is SyncConflictValue.Tombstone
                )
        ) {
            return SyncMutation.DeleteRecord(
                recordId = conflict.recordId,
                recordUpdatedAtEpochMillis = now,
                deletedAtEpochMillis = now,
            )
        }

        require(conflict.propertyPath.isNotEmpty()) {
            "Active local conflict must identify a field"
        }
        val lookup = lookupPath(record.fields, conflict.propertyPath)
        return if (lookup.present) {
            SyncMutation.SetField(
                recordId = conflict.recordId,
                propertyPath = conflict.propertyPath,
                value = checkNotNull(lookup.value),
                recordUpdatedAtEpochMillis = now,
            )
        } else {
            SyncMutation.RemoveField(
                recordId = conflict.recordId,
                propertyPath = conflict.propertyPath,
                recordUpdatedAtEpochMillis = now,
            )
        }
    }

    private fun SupabaseSyncSnapshot.toDocument(
        schemaVersion: Int,
        now: Long,
    ): SyncDocumentEnvelope {
        val revision = SyncRevision("supabase-snapshot", cursor)
        val mapped = records.associate { record ->
            var fields = JsonObject(emptyMap())
            record.fields.forEach { (fieldPath, value) ->
                fields = setPath(
                    root = fields,
                    path = SupabaseFieldPathCodec.decode(fieldPath),
                    value = value,
                )
            }
            record.recordId to SyncRecordEnvelope(
                id = record.recordId,
                revision = revision,
                updatedAtEpochMillis = now,
                deletedAtEpochMillis = now.takeIf { record.isDeleted },
                fields = fields,
            )
        }
        return SyncDocumentEnvelope(
            schemaVersion = schemaVersion,
            kind = documentKind,
            revision = revision,
            generatedAtEpochMillis = now,
            records = mapped,
        )
    }

    private fun SupabaseRemoteConflict.toSyncConflict(
        documentKind: tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind,
    ): SyncConflict {
        return SyncConflict(
            documentKind = documentKind,
            recordId = recordId,
            propertyPath = fieldPath?.let(SupabaseFieldPathCodec::decode).orEmpty(),
            kind = kind,
            base = SyncConflictValue.Missing,
            local = toConflictValue(localValue, kind),
            remote = toConflictValue(remoteValue, kind),
            remoteConflictId = conflictId,
        )
    }

    private fun toConflictValue(
        value: JsonElement?,
        kind: SyncConflictKind,
    ): SyncConflictValue {
        if (value == null || value is JsonNull) return SyncConflictValue.Missing
        val objectValue = value as? JsonObject ?: return SyncConflictValue.Present(value)

        return when (kind) {
            SyncConflictKind.FIELD_DIVERGENCE -> {
                if (objectValue["removed"]?.jsonPrimitive?.booleanOrNull == true) {
                    SyncConflictValue.Missing
                } else {
                    objectValue["value"]
                        ?.let(SyncConflictValue::Present)
                        ?: SyncConflictValue.Missing
                }
            }
            SyncConflictKind.DELETE_EDIT -> {
                if (objectValue["deleted"]?.jsonPrimitive?.booleanOrNull == true) {
                    SyncConflictValue.Tombstone
                } else {
                    SyncConflictValue.Present(objectValue)
                }
            }
        }
    }

    private fun lookupPath(
        root: JsonObject,
        path: List<String>,
    ): PathLookup {
        if (path.isEmpty()) return PathLookup(present = true, value = root)

        var current: JsonElement = root
        path.forEach { segment ->
            val objectValue = current as? JsonObject
                ?: return PathLookup(present = false, value = null)
            if (!objectValue.containsKey(segment)) {
                return PathLookup(present = false, value = null)
            }
            current = objectValue.getValue(segment)
        }
        return PathLookup(present = true, value = current)
    }

    private fun setPath(
        root: JsonObject,
        path: List<String>,
        value: JsonElement,
    ): JsonObject {
        require(path.isNotEmpty()) { "Sync field path must not be empty" }
        val key = path.first()
        val updated = root.toMutableMap()
        if (path.size == 1) {
            updated[key] = value
        } else {
            val child = root[key] as? JsonObject ?: JsonObject(emptyMap())
            updated[key] = setPath(child, path.drop(1), value)
        }
        return JsonObject(updated)
    }

    private fun removePath(
        root: JsonObject,
        path: List<String>,
    ): JsonObject {
        require(path.isNotEmpty()) { "Sync field path must not be empty" }
        val key = path.first()
        val updated = root.toMutableMap()
        if (path.size == 1) {
            updated.remove(key)
        } else {
            val child = root[key] as? JsonObject ?: return root
            updated[key] = removePath(child, path.drop(1))
        }
        return JsonObject(updated)
    }

    private fun failed(reason: SyncFailureReason): SyncConflictResolutionResult.Failed =
        SyncConflictResolutionResult.Failed(SyncFailure(reason))

    private data class PathLookup(
        val present: Boolean,
        val value: JsonElement?,
    )
}
