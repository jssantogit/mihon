package tachiyomi.domain.tsuzuki.sync.service

import java.util.UUID
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
import tachiyomi.domain.tsuzuki.sync.model.SupabaseSyncEvent
import tachiyomi.domain.tsuzuki.sync.model.SupabaseSyncOperation
import tachiyomi.domain.tsuzuki.sync.model.SupabaseSyncSnapshot
import tachiyomi.domain.tsuzuki.sync.model.SyncConflict
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictKind
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictValue
import tachiyomi.domain.tsuzuki.sync.model.SyncCycleReport
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentResult
import tachiyomi.domain.tsuzuki.sync.model.SyncFailure
import tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason
import tachiyomi.domain.tsuzuki.sync.model.SyncFrontier
import tachiyomi.domain.tsuzuki.sync.model.SyncMergeResult
import tachiyomi.domain.tsuzuki.sync.model.SyncMutation
import tachiyomi.domain.tsuzuki.sync.model.SyncRecordEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision
import tachiyomi.domain.tsuzuki.sync.model.SyncStoredState
import tachiyomi.domain.tsuzuki.sync.model.SyncTransportResult
import tachiyomi.domain.tsuzuki.sync.repository.SyncConflictRepository
import tachiyomi.domain.tsuzuki.sync.repository.SyncOutboxRepository
import tachiyomi.domain.tsuzuki.sync.repository.SyncStateRepository

class SupabaseSyncOrchestrator(
    private val accountRepository: AccountRepository,
    private val transport: SupabaseSyncTransport,
    private val identityClaimTransport: CanonicalIdentityClaimTransport,
    private val identityRepository: CanonicalIdentitySyncRepository,
    private val canonicalTitleMergePort: CanonicalTitleMergePort,
    private val outboxRepository: SyncOutboxRepository,
    private val stateRepository: SyncStateRepository,
    private val supabaseStateStore: SupabaseSyncStateStore,
    private val conflictRepository: SyncConflictRepository,
    adapters: List<SyncDocumentAdapter>,
    private val clientIdentityProvider: SyncClientIdentityProvider,
    private val clock: SyncClock,
    private val differ: SyncDocumentDiffer = SyncDocumentDiffer(),
    private val merger: ThreeWaySyncMerger = ThreeWaySyncMerger(),
    private val retryPolicy: SyncRetryPolicy = SyncRetryPolicy(),
    private val mutationIdSource: () -> String = { UUID.randomUUID().toString() },
) : SyncCycleRunner {

    private val mutex = Mutex()
    private val adapters = adapters.associateBy { it.documentKind }

    init {
        require(this.adapters.size == adapters.size) {
            "Supabase sync adapters must have unique document kinds"
        }
        require(SyncDocumentKind.MANIFEST !in this.adapters) {
            "Manifest is derived and cannot be a Supabase sync adapter"
        }
    }

    suspend fun sync(documentKind: SyncDocumentKind): SyncDocumentResult = mutex.withLock {
        if (accountRepository.state.value !is AccountState.Authenticated) {
            return@withLock authorizationFailure(documentKind)
        }

        reconcileCanonicalIdentities()?.let { failure ->
            return@withLock SyncDocumentResult.Failed(documentKind, failure)
        }

        syncDocument(documentKind, respectBackoff = false)
    }

    override suspend fun runOnce(): SyncCycleReport = mutex.withLock {
        if (accountRepository.state.value !is AccountState.Authenticated) {
            return@withLock SyncCycleReport(
                documentResults = emptyList(),
                globalFailure = SyncFailure(SyncFailureReason.AUTHORIZATION_REQUIRED),
            )
        }

        reconcileCanonicalIdentities()?.let { failure ->
            return@withLock SyncCycleReport(
                documentResults = emptyList(),
                globalFailure = failure,
            )
        }

        val results = mutableListOf<SyncDocumentResult>()
        for (documentKind in adapters.keys.sortedBy { it.ordinal }) {
            val result = syncDocument(documentKind, respectBackoff = true)
            results += result
            if (result is SyncDocumentResult.Failed && result.failure.reason.isCycleWideFailure()) {
                return@withLock SyncCycleReport(
                    documentResults = results,
                    globalFailure = result.failure,
                )
            }
        }

        SyncCycleReport(documentResults = results)
    }

    private suspend fun syncDocument(
        documentKind: SyncDocumentKind,
        respectBackoff: Boolean,
    ): SyncDocumentResult {
        val adapter = adapters[documentKind]
            ?: return SyncDocumentResult.Failed(
                documentKind,
                SyncFailure(SyncFailureReason.LOCAL_STATE_UNAVAILABLE),
            )

        val now = clock.nowEpochMillis()
        val outbox = outboxRepository.get(documentKind)
        val pendingAtStart = supabaseStateStore.getPending(documentKind)

        if (respectBackoff) {
            val deferredUntil = listOfNotNull(
                outbox?.nextAttemptAtEpochMillis,
                pendingAtStart?.nextAttemptAtEpochMillis,
            ).maxOrNull()
            if (deferredUntil != null && deferredUntil > now) {
                return SyncDocumentResult.Deferred(documentKind, deferredUntil)
            }
        }

        return try {
            val localBeforePull = adapter.exportDocument()
            if (localBeforePull.kind != documentKind) {
                return fail(
                    documentKind = documentKind,
                    pending = pendingAtStart,
                    outbox = outbox,
                    now = now,
                    failure = SyncFailure(SyncFailureReason.MALFORMED_DOCUMENT),
                )
            }

            val cursorState = supabaseStateStore.getCursor(documentKind)
            val stored = if (cursorState == null) {
                null
            } else {
                stateRepository.get(documentKind)
            }
            val acceptedBase = stored?.acceptedBase
            val schemaVersion = acceptedBase?.schemaVersion ?: localBeforePull.schemaVersion

            var pull = initialRemoteState(
                documentKind = documentKind,
                schemaVersion = schemaVersion,
                cursorState = cursorState,
                acceptedBase = acceptedBase,
                now = now,
            )
            if (pull is RemotePull.Failure) {
                return fail(
                    documentKind,
                    pendingAtStart,
                    outbox,
                    now,
                    pull.failure,
                )
            }
            pull as RemotePull.Success

            val existingStoredConflicts = conflictRepository
                .getForDocument(documentKind)
                .map { it.conflict }
            if (existingStoredConflicts.isNotEmpty()) {
                val blockedMerge = merge(
                    base = acceptedBase,
                    local = materializeLocalTombstones(
                        base = acceptedBase,
                        local = localBeforePull,
                        now = now,
                    ),
                    remote = pull.document,
                    now = now,
                )
                if (blockedMerge is LocalMerge.Failure) {
                    return fail(
                        documentKind = documentKind,
                        pending = pendingAtStart,
                        outbox = outbox,
                        now = now,
                        failure = blockedMerge.failure,
                    )
                }
                blockedMerge as LocalMerge.Success

                if (blockedMerge.requiresLocalApply) {
                    adapter.applyDocument(blockedMerge.document)
                }

                val retainedConflicts = deduplicateConflicts(
                    existingStoredConflicts + blockedMerge.conflicts,
                )
                persistAcceptedRemote(
                    documentKind = documentKind,
                    remote = pull.document,
                    cursor = pull.cursor,
                    lastSuccessfulSyncAt = cursorState?.lastSuccessfulSyncAtEpochMillis,
                    stored = stored,
                )
                recordConflicts(documentKind, retainedConflicts, now)
                ensureDirty(documentKind, outbox, now)
                return SyncDocumentResult.Conflict(
                    documentKind = documentKind,
                    conflictCount = retainedConflicts.size,
                )
            }

            val serverConflicts = mutableListOf<SyncConflict>()
            var remoteWritten = false

            if (pendingAtStart != null) {
                when (val replay = transport.push(pendingAtStart.batch)) {
                    is SyncTransportResult.Failure -> {
                        return fail(
                            documentKind,
                            pendingAtStart,
                            outbox,
                            now,
                            replay.failure,
                        )
                    }
                    is SyncTransportResult.Success -> {
                        remoteWritten = true
                        supabaseStateStore.deletePending(pendingAtStart.batch.mutationId)
                        serverConflicts += replay.value.conflicts.map {
                            it.toSyncConflict(documentKind)
                        }
                        val afterReplay = pullDelta(
                            documentKind = documentKind,
                            schemaVersion = schemaVersion,
                            startDocument = pull.document,
                            startCursor = pull.cursor,
                            now = now,
                        )
                        if (afterReplay is RemotePull.Failure) {
                            return fail(
                                documentKind,
                                pending = null,
                                outbox = outbox,
                                now = now,
                                failure = afterReplay.failure,
                            )
                        }
                        pull = afterReplay as RemotePull.Success
                        if (pull.cursor < replay.value.cursor) {
                            return fail(
                                documentKind,
                                pending = null,
                                outbox = outbox,
                                now = now,
                                failure = SyncFailure(
                                    SyncFailureReason.MALFORMED_REMOTE_DOCUMENT,
                                ),
                            )
                        }
                    }
                }
            }

            val localWithTombstones = materializeLocalTombstones(
                base = acceptedBase,
                local = localBeforePull,
                now = now,
            )
            val prePushMerge = merge(
                base = acceptedBase,
                local = localWithTombstones,
                remote = pull.document,
                now = now,
            )
            if (prePushMerge is LocalMerge.Failure) {
                return fail(
                    documentKind,
                    pending = null,
                    outbox = outbox,
                    now = now,
                    failure = prePushMerge.failure,
                )
            }
            prePushMerge as LocalMerge.Success

            if (prePushMerge.requiresLocalApply) {
                adapter.applyDocument(prePushMerge.document)
            }

            persistAcceptedRemote(
                documentKind = documentKind,
                remote = pull.document,
                cursor = pull.cursor,
                lastSuccessfulSyncAt = cursorState?.lastSuccessfulSyncAtEpochMillis,
                stored = stored,
            )

            val prePushConflicts = deduplicateConflicts(
                prePushMerge.conflicts + serverConflicts,
            )
            if (prePushConflicts.isNotEmpty()) {
                recordConflicts(documentKind, prePushConflicts, now)
                ensureDirty(documentKind, outbox, now)
                return SyncDocumentResult.Conflict(
                    documentKind = documentKind,
                    conflictCount = prePushConflicts.size,
                )
            }

            val localAfterPull = adapter.exportDocument()
            val localForDiff = materializeLocalTombstones(
                base = pull.document,
                local = localAfterPull,
                now = now,
            )
            val mutations = differ.diff(pull.document, localForDiff)

            if (mutations.isEmpty()) {
                markSuccessful(
                    documentKind = documentKind,
                    remote = pull.document,
                    cursor = pull.cursor,
                    stored = stored,
                    now = now,
                )
                conflictRepository.clearForDocument(documentKind)
                if (adapter.hasUnportableLocalState()) {
                    ensureDirty(
                        documentKind = documentKind,
                        outbox = outboxRepository.get(documentKind),
                        now = now,
                    )
                } else {
                    outboxRepository.clear(documentKind)
                }
                return SyncDocumentResult.Synchronized(
                    documentKind = documentKind,
                    localApplied = prePushMerge.requiresLocalApply,
                    remoteWritten = remoteWritten,
                )
            }

            ensureDirty(documentKind, outboxRepository.get(documentKind), now)

            val batch = SupabaseMutationBatch(
                mutationId = mutationIdSource(),
                originClientId = clientIdentityProvider.getOrCreate(),
                domain = documentKind.name,
                baseCursor = pull.cursor,
                operations = mutations,
            )
            val pending = SupabasePendingMutation(
                batch = batch,
                createdAtEpochMillis = now,
            )
            supabaseStateStore.putPending(pending)

            val pushed = when (val result = transport.push(batch)) {
                is SyncTransportResult.Failure -> {
                    return fail(
                        documentKind = documentKind,
                        pending = pending,
                        outbox = outboxRepository.get(documentKind),
                        now = now,
                        failure = result.failure,
                    )
                }
                is SyncTransportResult.Success -> result.value
            }
            remoteWritten = true
            supabaseStateStore.deletePending(batch.mutationId)

            val afterPush = pullDelta(
                documentKind = documentKind,
                schemaVersion = schemaVersion,
                startDocument = pull.document,
                startCursor = pull.cursor,
                now = now,
            )
            if (afterPush is RemotePull.Failure) {
                return fail(
                    documentKind,
                    pending = null,
                    outbox = outboxRepository.get(documentKind),
                    now = now,
                    failure = afterPush.failure,
                )
            }
            afterPush as RemotePull.Success
            if (afterPush.cursor < pushed.cursor) {
                return fail(
                    documentKind,
                    pending = null,
                    outbox = outboxRepository.get(documentKind),
                    now = now,
                    failure = SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT),
                )
            }

            val finalLocal = adapter.exportDocument()
            val finalMerge = merge(
                base = pull.document,
                local = materializeLocalTombstones(
                    base = pull.document,
                    local = finalLocal,
                    now = now,
                ),
                remote = afterPush.document,
                now = now,
            )
            if (finalMerge is LocalMerge.Failure) {
                return fail(
                    documentKind,
                    pending = null,
                    outbox = outboxRepository.get(documentKind),
                    now = now,
                    failure = finalMerge.failure,
                )
            }
            finalMerge as LocalMerge.Success

            if (finalMerge.requiresLocalApply) {
                adapter.applyDocument(finalMerge.document)
            }

            val finalConflicts = deduplicateConflicts(
                finalMerge.conflicts +
                    pushed.conflicts.map { it.toSyncConflict(documentKind) },
            )
            persistAcceptedRemote(
                documentKind = documentKind,
                remote = afterPush.document,
                cursor = afterPush.cursor,
                lastSuccessfulSyncAt = if (finalConflicts.isEmpty()) now else null,
                stored = stored,
            )

            if (finalConflicts.isNotEmpty()) {
                recordConflicts(documentKind, finalConflicts, now)
                ensureDirty(documentKind, outboxRepository.get(documentKind), now)
                return SyncDocumentResult.Conflict(
                    documentKind = documentKind,
                    conflictCount = finalConflicts.size,
                )
            }

            conflictRepository.clearForDocument(documentKind)
            if (adapter.hasUnportableLocalState()) {
                ensureDirty(
                    documentKind = documentKind,
                    outbox = outboxRepository.get(documentKind),
                    now = now,
                )
            } else {
                outboxRepository.clear(documentKind)
            }
            markSuccessful(
                documentKind = documentKind,
                remote = afterPush.document,
                cursor = afterPush.cursor,
                stored = stored,
                now = now,
            )

            SyncDocumentResult.Synchronized(
                documentKind = documentKind,
                localApplied = prePushMerge.requiresLocalApply ||
                    finalMerge.requiresLocalApply,
                remoteWritten = remoteWritten,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            fail(
                documentKind = documentKind,
                pending = supabaseStateStore.getPending(documentKind),
                outbox = outboxRepository.get(documentKind),
                now = now,
                failure = SyncFailure(SyncFailureReason.LOCAL_STATE_UNAVAILABLE),
            )
        }
    }

    private suspend fun initialRemoteState(
        documentKind: SyncDocumentKind,
        schemaVersion: Int,
        cursorState: tachiyomi.domain.tsuzuki.sync.model.SupabaseSyncCursor?,
        acceptedBase: SyncDocumentEnvelope?,
        now: Long,
    ): RemotePull {
        if (cursorState != null && acceptedBase == null && cursorState.eventCursor > 0) {
            return when (val snapshot = transport.snapshot(documentKind)) {
                is SyncTransportResult.Failure -> RemotePull.Failure(snapshot.failure)
                is SyncTransportResult.Success -> {
                    val document = snapshot.value.toDocument(schemaVersion, now)
                    pullDelta(
                        documentKind = documentKind,
                        schemaVersion = schemaVersion,
                        startDocument = document,
                        startCursor = snapshot.value.cursor,
                        now = now,
                    )
                }
            }
        }

        val startCursor = cursorState?.eventCursor ?: 0
        val startDocument = acceptedBase ?: emptyRemoteDocument(
            documentKind = documentKind,
            schemaVersion = schemaVersion,
            cursor = startCursor,
            now = now,
        )
        return pullDelta(
            documentKind = documentKind,
            schemaVersion = schemaVersion,
            startDocument = startDocument,
            startCursor = startCursor,
            now = now,
        )
    }

    private suspend fun pullDelta(
        documentKind: SyncDocumentKind,
        schemaVersion: Int,
        startDocument: SyncDocumentEnvelope,
        startCursor: Long,
        now: Long,
    ): RemotePull {
        var cursor = startCursor
        var document = startDocument

        while (true) {
            when (
                val pulled = transport.pullDelta(
                    documentKind = documentKind,
                    sinceEventId = cursor,
                    limit = SupabaseSyncTransport.DEFAULT_DELTA_LIMIT,
                )
            ) {
                is SyncTransportResult.Failure -> return RemotePull.Failure(pulled.failure)
                is SyncTransportResult.Success -> {
                    val events = pulled.value
                    if (events.isEmpty()) {
                        return RemotePull.Success(document, cursor)
                    }
                    if (
                        events.any { it.eventId <= cursor } ||
                        events.zipWithNext().any { (left, right) ->
                            right.eventId <= left.eventId
                        }
                    ) {
                        return RemotePull.Failure(
                            SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT),
                        )
                    }

                    document = applyRemoteEvents(
                        base = document,
                        events = events,
                        schemaVersion = schemaVersion,
                        now = now,
                    )
                    cursor = events.last().eventId

                    if (events.size < SupabaseSyncTransport.DEFAULT_DELTA_LIMIT) {
                        return RemotePull.Success(document, cursor)
                    }
                }
            }
        }
    }

    private fun applyRemoteEvents(
        base: SyncDocumentEnvelope,
        events: List<SupabaseSyncEvent>,
        schemaVersion: Int,
        now: Long,
    ): SyncDocumentEnvelope {
        val records = base.records.toMutableMap()
        events.forEach { event ->
            val previous = records[event.recordId]
            val revision = SyncRevision("supabase", event.eventId)
            when (event.operation) {
                SupabaseSyncOperation.DELETE -> {
                    records[event.recordId] = SyncRecordEnvelope(
                        id = event.recordId,
                        revision = revision,
                        updatedAtEpochMillis = now,
                        deletedAtEpochMillis = now,
                        fields = previous?.fields ?: JsonObject(emptyMap()),
                    )
                }
                SupabaseSyncOperation.SET -> {
                    val path = SupabaseFieldPathCodec.decode(checkNotNull(event.fieldPath))
                    val value = event.value ?: JsonNull
                    records[event.recordId] = SyncRecordEnvelope(
                        id = event.recordId,
                        revision = revision,
                        updatedAtEpochMillis = now,
                        fields = setPath(
                            root = previous?.fields ?: JsonObject(emptyMap()),
                            path = path,
                            value = value,
                        ),
                    )
                }
                SupabaseSyncOperation.REMOVE -> {
                    val path = SupabaseFieldPathCodec.decode(checkNotNull(event.fieldPath))
                    records[event.recordId] = SyncRecordEnvelope(
                        id = event.recordId,
                        revision = revision,
                        updatedAtEpochMillis = now,
                        fields = removePath(
                            root = previous?.fields ?: JsonObject(emptyMap()),
                            path = path,
                        ),
                    )
                }
            }
        }

        val cursor = events.lastOrNull()?.eventId ?: base.revision.sequence
        return SyncDocumentEnvelope(
            schemaVersion = schemaVersion,
            kind = base.kind,
            revision = SyncRevision("supabase", cursor),
            generatedAtEpochMillis = now,
            records = records,
        )
    }

    private fun SupabaseSyncSnapshot.toDocument(
        schemaVersion: Int,
        now: Long,
    ): SyncDocumentEnvelope {
        val revision = SyncRevision("supabase", cursor)
        val mapped = records.associate { record ->
            var materializedFields = JsonObject(emptyMap())
            record.fields.forEach { (fieldPath, value) ->
                materializedFields = setPath(
                    root = materializedFields,
                    path = SupabaseFieldPathCodec.decode(fieldPath),
                    value = value,
                )
            }
            record.recordId to SyncRecordEnvelope(
                id = record.recordId,
                revision = revision,
                updatedAtEpochMillis = now,
                deletedAtEpochMillis = now.takeIf { record.isDeleted },
                fields = materializedFields,
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

    private fun emptyRemoteDocument(
        documentKind: SyncDocumentKind,
        schemaVersion: Int,
        cursor: Long,
        now: Long,
    ) = SyncDocumentEnvelope(
        schemaVersion = schemaVersion,
        kind = documentKind,
        revision = SyncRevision("supabase", cursor),
        generatedAtEpochMillis = now,
        records = emptyMap(),
    )

    private fun merge(
        base: SyncDocumentEnvelope?,
        local: SyncDocumentEnvelope,
        remote: SyncDocumentEnvelope,
        now: Long,
    ): LocalMerge {
        return when (
            val result = merger.merge(
                base = base,
                local = local,
                remote = remote,
                mergeRevision = SyncRevision("supabase-merge", now),
                mergedAtEpochMillis = now,
            )
        ) {
            is SyncMergeResult.Rejected -> LocalMerge.Failure(result.failure)
            is SyncMergeResult.Success -> LocalMerge.Success(
                document = SyncDocumentEnvelope(
                    schemaVersion = result.outcome.schemaVersion,
                    kind = result.outcome.documentKind,
                    revision = SyncRevision("supabase-merge", now),
                    generatedAtEpochMillis = now,
                    records = result.outcome.records,
                ),
                conflicts = result.outcome.conflicts,
                requiresLocalApply = result.outcome.requiresLocalApply,
            )
        }
    }

    private fun materializeLocalTombstones(
        base: SyncDocumentEnvelope?,
        local: SyncDocumentEnvelope,
        now: Long,
    ): SyncDocumentEnvelope {
        if (
            base == null ||
            base.kind != local.kind ||
            base.schemaVersion != local.schemaVersion
        ) {
            return local
        }

        val missing = base.records.keys - local.records.keys
        if (missing.isEmpty()) return local

        val records = local.records.toMutableMap()
        missing.sorted().forEach { recordId ->
            val previous = base.records.getValue(recordId)
            records[recordId] = previous.copy(
                revision = SyncRevision("local-delete", now),
                updatedAtEpochMillis = now,
                deletedAtEpochMillis = now,
            )
        }
        return local.copy(records = records)
    }

    private suspend fun persistAcceptedRemote(
        documentKind: SyncDocumentKind,
        remote: SyncDocumentEnvelope,
        cursor: Long,
        lastSuccessfulSyncAt: Long?,
        stored: SyncStoredState?,
    ) {
        stateRepository.put(
            SyncStoredState(
                documentKind = documentKind,
                acceptedBase = remote,
                remoteRevision = null,
                lastSuccessfulSyncAtEpochMillis = lastSuccessfulSyncAt,
                acceptedFrontier = stored?.acceptedFrontier ?: SyncFrontier(),
            ),
        )
        supabaseStateStore.putCursor(
            documentKind = documentKind,
            eventCursor = cursor,
            lastSuccessfulSyncAtEpochMillis = lastSuccessfulSyncAt,
        )
    }

    private suspend fun markSuccessful(
        documentKind: SyncDocumentKind,
        remote: SyncDocumentEnvelope,
        cursor: Long,
        stored: SyncStoredState?,
        now: Long,
    ) {
        persistAcceptedRemote(
            documentKind = documentKind,
            remote = remote,
            cursor = cursor,
            lastSuccessfulSyncAt = now,
            stored = stored,
        )
    }

    private suspend fun reconcileCanonicalIdentities(): SyncFailure? {
        val remapped = mutableMapOf<String, String>()
        for (
            identity in identityRepository.getVerifiedIdentities()
                .sortedWith(
                    compareBy(
                        VerifiedCanonicalIdentity::provider,
                        VerifiedCanonicalIdentity::externalId,
                    ),
                )
        ) {
            val proposed = remapped[identity.canonicalTitleId] ?: identity.canonicalTitleId
            val claimed = when (
                val result = identityClaimTransport.claim(
                    provider = identity.provider,
                    externalId = identity.externalId,
                    proposedCanonicalTitleId = proposed,
                )
            ) {
                is SyncTransportResult.Failure -> return result.failure
                is SyncTransportResult.Success -> result.value
            }
            if (claimed.isBlank()) {
                return SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT)
            }

            if (claimed != proposed) {
                canonicalTitleMergePort.merge(
                    targetId = claimed,
                    localId = proposed,
                ).getOrElse {
                    return SyncFailure(SyncFailureReason.LOCAL_STATE_UNAVAILABLE)
                }
            }
            remapped[identity.canonicalTitleId] = claimed
            remapped[proposed] = claimed
        }
        return null
    }

    private suspend fun recordConflicts(
        documentKind: SyncDocumentKind,
        conflicts: List<SyncConflict>,
        now: Long,
    ) {
        conflictRepository.replaceForDocument(
            documentKind = documentKind,
            conflicts = conflicts,
            createdAtEpochMillis = now,
        )
    }

    private suspend fun ensureDirty(
        documentKind: SyncDocumentKind,
        outbox: tachiyomi.domain.tsuzuki.sync.model.SyncOutboxEntry?,
        now: Long,
    ) {
        if (outbox == null) {
            outboxRepository.markDirty(documentKind, now)
        }
    }

    private suspend fun fail(
        documentKind: SyncDocumentKind,
        pending: SupabasePendingMutation?,
        outbox: tachiyomi.domain.tsuzuki.sync.model.SyncOutboxEntry?,
        now: Long,
        failure: SyncFailure,
    ): SyncDocumentResult.Failed {
        ensureDirty(documentKind, outbox, now)
        val attemptCount = pending?.attemptCount ?: outbox?.attemptCount ?: 0
        val nextAttemptAt = retryPolicy.nextAttemptAt(
            nowEpochMillis = now,
            currentAttemptCount = attemptCount,
            failure = failure,
        )
        if (pending != null) {
            supabaseStateStore.recordPendingFailure(
                mutation = pending,
                nextAttemptAtEpochMillis = nextAttemptAt,
            )
        }
        outboxRepository.recordFailure(
            documentKind = documentKind,
            nextAttemptAtEpochMillis = nextAttemptAt,
        )
        return SyncDocumentResult.Failed(documentKind, failure)
    }

    private fun authorizationFailure(
        documentKind: SyncDocumentKind,
    ) = SyncDocumentResult.Failed(
        documentKind = documentKind,
        failure = SyncFailure(SyncFailureReason.AUTHORIZATION_REQUIRED),
    )

    private fun SupabaseRemoteConflict.toSyncConflict(
        documentKind: SyncDocumentKind,
    ): SyncConflict {
        val path = fieldPath?.let(SupabaseFieldPathCodec::decode).orEmpty()
        return SyncConflict(
            documentKind = documentKind,
            recordId = recordId,
            propertyPath = path,
            kind = kind,
            base = SyncConflictValue.Missing,
            local = toConflictValue(localValue, kind),
            remote = toConflictValue(remoteValue, kind),
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

    private fun deduplicateConflicts(
        conflicts: List<SyncConflict>,
    ): List<SyncConflict> {
        return conflicts.distinctBy {
            Triple(
                it.recordId,
                it.propertyPath,
                it.kind,
            )
        }
    }

    private fun setPath(
        root: JsonObject,
        path: List<String>,
        value: JsonElement,
    ): JsonObject {
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

    private fun SyncFailureReason.isCycleWideFailure(): Boolean {
        return this == SyncFailureReason.AUTHORIZATION_REQUIRED ||
            this == SyncFailureReason.NETWORK_UNAVAILABLE ||
            this == SyncFailureReason.REMOTE_UNAVAILABLE ||
            this == SyncFailureReason.RATE_LIMITED
    }

    private sealed interface RemotePull {
        data class Success(
            val document: SyncDocumentEnvelope,
            val cursor: Long,
        ) : RemotePull

        data class Failure(
            val failure: SyncFailure,
        ) : RemotePull
    }

    private sealed interface LocalMerge {
        data class Success(
            val document: SyncDocumentEnvelope,
            val conflicts: List<SyncConflict>,
            val requiresLocalApply: Boolean,
        ) : LocalMerge

        data class Failure(
            val failure: SyncFailure,
        ) : LocalMerge
    }
}
