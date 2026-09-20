package tachiyomi.domain.tsuzuki.sync.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.tsuzuki.sync.model.SyncCodecResult
import tachiyomi.domain.tsuzuki.sync.model.SyncCycleReport
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentResult
import tachiyomi.domain.tsuzuki.sync.model.SyncFailure
import tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason
import tachiyomi.domain.tsuzuki.sync.model.SyncFrontier
import tachiyomi.domain.tsuzuki.sync.model.SyncMergeResult
import tachiyomi.domain.tsuzuki.sync.model.SyncMutationBatch
import tachiyomi.domain.tsuzuki.sync.model.SyncOutboxEntry
import tachiyomi.domain.tsuzuki.sync.model.SyncRemoteContent
import tachiyomi.domain.tsuzuki.sync.model.SyncRemoteFile
import tachiyomi.domain.tsuzuki.sync.model.SyncReplicaJournal
import tachiyomi.domain.tsuzuki.sync.model.SyncReplicaMaterializationResult
import tachiyomi.domain.tsuzuki.sync.model.SyncReplicaState
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision
import tachiyomi.domain.tsuzuki.sync.model.SyncStoredState
import tachiyomi.domain.tsuzuki.sync.model.SyncTransportResult
import tachiyomi.domain.tsuzuki.sync.repository.SyncConflictRepository
import tachiyomi.domain.tsuzuki.sync.repository.SyncOutboxRepository
import tachiyomi.domain.tsuzuki.sync.repository.SyncReplicaRepository
import tachiyomi.domain.tsuzuki.sync.repository.SyncStateRepository

class SyncCycleOrchestrator(
    private val transport: DriveSyncTransport,
    private val outboxRepository: SyncOutboxRepository,
    private val stateRepository: SyncStateRepository,
    private val replicaRepository: SyncReplicaRepository,
    private val conflictRepository: SyncConflictRepository,
    adapters: List<SyncDocumentAdapter>,
    private val codec: SyncDocumentCodec,
    private val journalCodec: SyncReplicaJournalCodec,
    private val differ: SyncDocumentDiffer,
    private val materializer: SyncReplicaMaterializer,
    private val bootstrap: SyncReplicaBootstrap,
    private val merger: ThreeWaySyncMerger,
    private val revisionSource: SyncRevisionSource,
    private val clock: SyncClock,
    private val retryPolicy: SyncRetryPolicy = SyncRetryPolicy(),
) : SyncCycleRunner {

    private val mutex = Mutex()
    private val adapters = adapters.sortedBy { it.documentKind.syncApplyOrder }

    init {
        require(this.adapters.map { it.documentKind }.distinct().size == this.adapters.size) {
            "Sync document adapters must have unique document kinds"
        }
        require(this.adapters.none { it.documentKind == SyncDocumentKind.MANIFEST }) {
            "Manifest is derived and cannot have a domain adapter"
        }
        require(this.adapters.none { it.documentKind == SyncDocumentKind.FALLBACK_PROGRESS }) {
            "Fallback progress remains disabled until tracker reconciliation"
        }
    }

    override suspend fun runOnce(): SyncCycleReport = mutex.withLock {
        runCycle()
    }

    private suspend fun runCycle(): SyncCycleReport {
        val now = clock.nowEpochMillis()
        val remoteFiles = when (val listed = transport.listFiles()) {
            is SyncTransportResult.Success -> listed.value
            is SyncTransportResult.Failure -> {
                return SyncCycleReport(
                    documentResults = emptyList(),
                    globalFailure = listed.failure,
                )
            }
        }

        val results = mutableListOf<SyncDocumentResult>()
        for (adapter in adapters) {
            val pending = outboxRepository.get(adapter.documentKind)
            if (pending?.nextAttemptAtEpochMillis?.let { it > now } == true) {
                results += SyncDocumentResult.Deferred(
                    documentKind = adapter.documentKind,
                    nextAttemptAtEpochMillis = pending.nextAttemptAtEpochMillis,
                )
                continue
            }

            val result = syncDocument(
                adapter = adapter,
                remoteFiles = remoteFiles,
                pending = pending,
                nowEpochMillis = now,
            )
            results += result
            if (result is SyncDocumentResult.Failed && result.failure.reason.isCycleWideFailure()) {
                return SyncCycleReport(
                    documentResults = results,
                    globalFailure = result.failure,
                )
            }
        }

        return SyncCycleReport(documentResults = results)
    }

    private suspend fun syncDocument(
        adapter: SyncDocumentAdapter,
        remoteFiles: List<SyncRemoteFile>,
        pending: SyncOutboxEntry?,
        nowEpochMillis: Long,
    ): SyncDocumentResult {
        val kind = adapter.documentKind
        return try {
            val replicaFiles = remoteFiles.filter {
                it.protocolVersion == PROTOCOL_VERSION && it.logicalKind == kind
            }
            if (
                replicaFiles
                    .groupBy { it.ownerDeviceId }
                    .values
                    .any { it.size > 1 }
            ) {
                return failDocument(
                    kind,
                    pending,
                    nowEpochMillis,
                    SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT),
                )
            }
            if (remoteFiles.any {
                    it.protocolVersion != null &&
                        it.protocolVersion != PROTOCOL_VERSION &&
                        it.logicalKind == kind
                }
            ) {
                return failDocument(
                    kind,
                    pending,
                    nowEpochMillis,
                    SyncFailure(SyncFailureReason.UNSUPPORTED_SCHEMA),
                )
            }

            val legacyFiles = remoteFiles.filter {
                it.protocolVersion == null && it.name == kind.fileName
            }
            if (legacyFiles.size > 1) {
                return failDocument(
                    kind,
                    pending,
                    nowEpochMillis,
                    SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT),
                )
            }

            val replicaDocuments = mutableListOf<ReplicaDocument>()
            for (file in replicaFiles) {
                val downloaded = download(file)
                    ?: return failDocument(
                        kind,
                        pending,
                        nowEpochMillis,
                        lastDownloadFailure ?: SyncFailure(SyncFailureReason.UNKNOWN),
                    )
                val journal = when (val decoded = journalCodec.decode(downloaded.content)) {
                    is SyncCodecResult.Success -> decoded.value
                    is SyncCodecResult.Failure -> {
                        return failDocument(kind, pending, nowEpochMillis, decoded.failure)
                    }
                }
                if (
                    journal.kind != kind ||
                    journal.ownerDeviceId != file.ownerDeviceId ||
                    file.protocolVersion != journal.protocolVersion
                ) {
                    return failDocument(
                        kind,
                        pending,
                        nowEpochMillis,
                        SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT),
                    )
                }
                replicaDocuments += ReplicaDocument(file, journal)
            }

            val legacyContents = mutableListOf<SyncRemoteContent>()
            for (file in legacyFiles) {
                val downloaded = download(file)
                    ?: return failDocument(
                        kind,
                        pending,
                        nowEpochMillis,
                        lastDownloadFailure ?: SyncFailure(SyncFailureReason.UNKNOWN),
                    )
                legacyContents += downloaded
            }

            val bootstrapResult = bootstrap.resolveGenesis(
                documentKind = kind,
                legacyFiles = legacyContents,
                journals = replicaDocuments.map { it.journal },
            )
            val bootstrapState = when (bootstrapResult) {
                is SyncReplicaBootstrapResult.Success -> bootstrapResult
                is SyncReplicaBootstrapResult.Failure -> {
                    return failDocument(kind, pending, nowEpochMillis, bootstrapResult.failure)
                }
            }

            val stored = stateRepository.get(kind)
            val exported = adapter.exportDocument()
            if (exported.kind != kind) {
                return failDocument(
                    kind,
                    pending,
                    nowEpochMillis,
                    SyncFailure(SyncFailureReason.MALFORMED_DOCUMENT),
                )
            }

            val schemaVersion = bootstrapState.genesis?.document?.schemaVersion
                ?: exported.schemaVersion
            if (schemaVersion != exported.schemaVersion) {
                return failDocument(
                    kind,
                    pending,
                    nowEpochMillis,
                    SyncFailure(SyncFailureReason.UNSUPPORTED_SCHEMA),
                )
            }

            val remoteMaterialized = materializeRemote(
                kind = kind,
                schemaVersion = schemaVersion,
                replicaDocuments = replicaDocuments,
                genesisDocument = bootstrapState.genesis?.document,
                nowEpochMillis = nowEpochMillis,
            )
            if (remoteMaterialized is SyncReplicaMaterializationResult.Failure) {
                return failDocument(kind, pending, nowEpochMillis, remoteMaterialized.failure)
            }
            remoteMaterialized as SyncReplicaMaterializationResult.Success
            if (remoteMaterialized.conflicts.isNotEmpty()) {
                return recordConflict(
                    kind = kind,
                    pending = pending,
                    conflicts = remoteMaterialized.conflicts,
                    nowEpochMillis = nowEpochMillis,
                )
            }

            val localReplica = replicaDocuments.singleOrNull {
                it.journal.ownerDeviceId == revisionSource.deviceId
            }
            val firstLegacyMigration = bootstrapState.genesis != null &&
                localReplica == null &&
                (stored == null || stored.acceptedFrontier.entries.isEmpty())

            var targetLocal = exported
            var diffBase = stored?.acceptedBase
            var observedFrontier = stored?.acceptedFrontier ?: SyncFrontier()
            val forceReplicaCreate = replicaDocuments.isEmpty()

            if (firstLegacyMigration) {
                val legacyMerge = mergeLegacyBootstrap(
                    adapter = adapter,
                    stored = stored,
                    exported = exported,
                    legacy = checkNotNull(bootstrapState.genesis).document,
                    nowEpochMillis = nowEpochMillis,
                )
                when (legacyMerge) {
                    is LegacyBootstrapMerge.Failure -> {
                        return failDocument(kind, pending, nowEpochMillis, legacyMerge.failure)
                    }
                    is LegacyBootstrapMerge.Conflict -> {
                        return recordConflict(
                            kind = kind,
                            pending = pending,
                            conflicts = legacyMerge.conflicts,
                            nowEpochMillis = nowEpochMillis,
                        )
                    }
                    is LegacyBootstrapMerge.Success -> {
                        targetLocal = legacyMerge.document
                        diffBase = bootstrapState.genesis.document
                        observedFrontier = SyncFrontier()
                    }
                }
            }

            val localReplicaState = reconcileReplicaState(
                kind = kind,
                localReplica = localReplica,
            )

            val unacceptedLocalBatch = localReplica
                ?.journal
                ?.batches
                ?.filter { batch -> stored?.acceptedFrontier?.observes(batch.revision) != true }
                ?.maxByOrNull { it.revision.sequence }

            if (unacceptedLocalBatch != null) {
                val visible = unacceptedLocalBatch.observed.advance(unacceptedLocalBatch.revision)
                val reconstructed = materializer.materialize(
                    kind = kind,
                    schemaVersion = schemaVersion,
                    journals = replicaDocuments.map { it.journal },
                    materializedAtEpochMillis = nowEpochMillis,
                    visibleFrontier = visible,
                )
                when (reconstructed) {
                    is SyncReplicaMaterializationResult.Failure -> {
                        return failDocument(kind, pending, nowEpochMillis, reconstructed.failure)
                    }
                    is SyncReplicaMaterializationResult.Success -> {
                        if (reconstructed.conflicts.isNotEmpty()) {
                            return recordConflict(
                                kind = kind,
                                pending = pending,
                                conflicts = reconstructed.conflicts,
                                nowEpochMillis = nowEpochMillis,
                            )
                        }
                        diffBase = reconstructed.document
                        observedFrontier = reconstructed.frontier
                    }
                }
            }

            val shouldConsiderLocal = pending != null ||
                stored == null ||
                forceReplicaCreate ||
                firstLegacyMigration
            val mutations = if (shouldConsiderLocal) {
                differ.diff(diffBase, targetLocal)
            } else {
                emptyList()
            }

            var replicaState = localReplicaState
            val candidateBatch = if (mutations.isNotEmpty()) {
                val sequence = replicaState.nextSequence
                val batch = SyncMutationBatch(
                    revision = SyncRevision(
                        deviceId = revisionSource.deviceId,
                        sequence = sequence,
                    ),
                    observed = observedFrontier,
                    generatedAtEpochMillis = nowEpochMillis,
                    mutations = mutations,
                )
                replicaState = replicaState.copy(nextSequence = sequence + 1)
                replicaRepository.put(replicaState)
                batch
            } else {
                null
            }

            val desiredJournal = SyncReplicaJournal(
                kind = kind,
                ownerDeviceId = revisionSource.deviceId,
                genesis = bootstrapState.genesis,
                batches = localReplica?.journal?.batches.orEmpty() +
                    listOfNotNull(candidateBatch),
            )
            val needsRemoteWrite = candidateBatch != null ||
                (localReplica == null && forceReplicaCreate)

            var acceptedLocalReplica = localReplica
            if (needsRemoteWrite) {
                val encoded = when (val result = journalCodec.encode(desiredJournal)) {
                    is SyncCodecResult.Success -> result.value
                    is SyncCodecResult.Failure -> {
                        return failDocument(kind, pending, nowEpochMillis, result.failure)
                    }
                }

                val written = if (localReplica != null) {
                    when (
                        val updated = transport.updateOwnedReplica(
                            file = localReplica.file,
                            ownerDeviceId = revisionSource.deviceId,
                            content = encoded,
                        )
                    ) {
                        is SyncTransportResult.Success -> updated.value
                        is SyncTransportResult.Failure -> {
                            return failDocument(kind, pending, nowEpochMillis, updated.failure)
                        }
                    }
                } else {
                    val createResult = createOrAdoptReplica(
                        kind = kind,
                        state = replicaState,
                        journal = desiredJournal,
                        content = encoded,
                    )
                    when (createResult) {
                        is ReplicaWriteResult.Success -> {
                            replicaState = createResult.state
                            createResult.file
                        }
                        is ReplicaWriteResult.Failure -> {
                            return failDocument(
                                kind,
                                pending,
                                nowEpochMillis,
                                createResult.failure,
                            )
                        }
                    }
                }

                replicaState = replicaState.copy(
                    reservedRemoteId = written.remoteId,
                    lastRemoteRevisionToken = written.revision.revisionToken,
                )
                replicaRepository.put(replicaState)
                acceptedLocalReplica = ReplicaDocument(written, desiredJournal)
            }

            val finalReplicas = replicaDocuments
                .filterNot { it.journal.ownerDeviceId == revisionSource.deviceId }
                .toMutableList()
                .apply {
                    acceptedLocalReplica?.let(::add)
                }

            val finalMaterialized = materializeRemote(
                kind = kind,
                schemaVersion = schemaVersion,
                replicaDocuments = finalReplicas,
                genesisDocument = bootstrapState.genesis?.document,
                nowEpochMillis = nowEpochMillis,
            )
            if (finalMaterialized is SyncReplicaMaterializationResult.Failure) {
                return failDocument(kind, pending, nowEpochMillis, finalMaterialized.failure)
            }
            finalMaterialized as SyncReplicaMaterializationResult.Success
            if (finalMaterialized.conflicts.isNotEmpty()) {
                return recordConflict(
                    kind = kind,
                    pending = pending,
                    conflicts = finalMaterialized.conflicts,
                    nowEpochMillis = nowEpochMillis,
                )
            }

            val requiresLocalApply = differ.diff(
                exported,
                finalMaterialized.document,
            ).isNotEmpty()
            if (requiresLocalApply) {
                adapter.applyDocument(finalMaterialized.document)
            }

            stateRepository.put(
                SyncStoredState(
                    documentKind = kind,
                    acceptedBase = finalMaterialized.document,
                    remoteRevision = bootstrapState.legacyFile?.revision,
                    lastSuccessfulSyncAtEpochMillis = nowEpochMillis,
                    acceptedFrontier = finalMaterialized.frontier,
                ),
            )
            conflictRepository.clearForDocument(kind)
            outboxRepository.clear(kind)

            SyncDocumentResult.Synchronized(
                documentKind = kind,
                localApplied = requiresLocalApply,
                remoteWritten = needsRemoteWrite,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            failDocument(
                documentKind = kind,
                pending = pending,
                nowEpochMillis = nowEpochMillis,
                failure = SyncFailure(SyncFailureReason.LOCAL_STATE_UNAVAILABLE),
            )
        }
    }

    private var lastDownloadFailure: SyncFailure? = null

    private suspend fun download(file: SyncRemoteFile): SyncRemoteContent? {
        return when (val result = transport.download(file)) {
            is SyncTransportResult.Success -> {
                lastDownloadFailure = null
                result.value
            }
            is SyncTransportResult.Failure -> {
                lastDownloadFailure = result.failure
                null
            }
        }
    }

    private fun materializeRemote(
        kind: SyncDocumentKind,
        schemaVersion: Int,
        replicaDocuments: List<ReplicaDocument>,
        genesisDocument: SyncDocumentEnvelope?,
        nowEpochMillis: Long,
    ): SyncReplicaMaterializationResult {
        if (replicaDocuments.isEmpty()) {
            return SyncReplicaMaterializationResult.Success(
                document = genesisDocument ?: SyncDocumentEnvelope(
                    schemaVersion = schemaVersion,
                    kind = kind,
                    revision = SyncRevision("materialized", 0),
                    generatedAtEpochMillis = nowEpochMillis,
                    records = emptyMap(),
                ),
                frontier = SyncFrontier(),
                conflicts = emptyList(),
            )
        }
        return materializer.materialize(
            kind = kind,
            schemaVersion = schemaVersion,
            journals = replicaDocuments.map { it.journal },
            materializedAtEpochMillis = nowEpochMillis,
        )
    }

    private suspend fun reconcileReplicaState(
        kind: SyncDocumentKind,
        localReplica: ReplicaDocument?,
    ): SyncReplicaState {
        val existing = replicaRepository.get(kind, revisionSource.deviceId)
        val nextFromRemote = (
            localReplica
                ?.journal
                ?.batches
                ?.maxOfOrNull { it.revision.sequence }
                ?.plus(1)
                ?: 1L
            ).coerceAtLeast(1L)
        val reconciled = existing?.copy(
            reservedRemoteId = localReplica?.file?.remoteId ?: existing.reservedRemoteId,
            lastRemoteRevisionToken =
            localReplica?.file?.revision?.revisionToken ?: existing.lastRemoteRevisionToken,
            nextSequence = maxOf(existing.nextSequence, nextFromRemote),
        )
            ?: SyncReplicaState(
                documentKind = kind,
                ownerDeviceId = revisionSource.deviceId,
                reservedRemoteId = localReplica?.file?.remoteId,
                lastRemoteRevisionToken = localReplica?.file?.revision?.revisionToken,
                nextSequence = nextFromRemote,
            )
        if (existing != reconciled) {
            replicaRepository.put(reconciled)
        }
        return reconciled
    }

    private suspend fun createOrAdoptReplica(
        kind: SyncDocumentKind,
        state: SyncReplicaState,
        journal: SyncReplicaJournal,
        content: String,
    ): ReplicaWriteResult {
        var currentState = state
        val remoteId = currentState.reservedRemoteId ?: when (val generated = transport.generateFileId()) {
            is SyncTransportResult.Success -> {
                currentState = currentState.copy(reservedRemoteId = generated.value)
                replicaRepository.put(currentState)
                generated.value
            }
            is SyncTransportResult.Failure -> {
                return ReplicaWriteResult.Failure(generated.failure)
            }
        }

        return when (
            val created = transport.createReplica(
                remoteId = remoteId,
                documentKind = kind,
                ownerDeviceId = revisionSource.deviceId,
                content = content,
            )
        ) {
            is SyncTransportResult.Success -> ReplicaWriteResult.Success(
                file = created.value,
                state = currentState.copy(
                    reservedRemoteId = created.value.remoteId,
                    lastRemoteRevisionToken = created.value.revision.revisionToken,
                ),
            )

            is SyncTransportResult.Failure -> {
                if (created.failure.reason != SyncFailureReason.REMOTE_CHANGED) {
                    return ReplicaWriteResult.Failure(created.failure)
                }
                val adopted = when (val existing = transport.getFile(remoteId)) {
                    is SyncTransportResult.Success -> existing.value
                    is SyncTransportResult.Failure -> {
                        return ReplicaWriteResult.Failure(existing.failure)
                    }
                }
                if (
                    adopted.protocolVersion != PROTOCOL_VERSION ||
                    adopted.logicalKind != kind ||
                    adopted.ownerDeviceId != revisionSource.deviceId
                ) {
                    return ReplicaWriteResult.Failure(
                        SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT),
                    )
                }
                val remoteContent = when (val downloaded = transport.download(adopted)) {
                    is SyncTransportResult.Success -> downloaded.value.content
                    is SyncTransportResult.Failure -> {
                        return ReplicaWriteResult.Failure(downloaded.failure)
                    }
                }
                val remoteJournal = when (val decoded = journalCodec.decode(remoteContent)) {
                    is SyncCodecResult.Success -> decoded.value
                    is SyncCodecResult.Failure -> {
                        return ReplicaWriteResult.Failure(decoded.failure)
                    }
                }
                if (remoteJournal != journal) {
                    return ReplicaWriteResult.Failure(
                        SyncFailure(SyncFailureReason.REMOTE_CHANGED),
                    )
                }
                ReplicaWriteResult.Success(
                    file = adopted,
                    state = currentState.copy(
                        reservedRemoteId = adopted.remoteId,
                        lastRemoteRevisionToken = adopted.revision.revisionToken,
                    ),
                )
            }
        }
    }

    private fun mergeLegacyBootstrap(
        adapter: SyncDocumentAdapter,
        stored: SyncStoredState?,
        exported: SyncDocumentEnvelope,
        legacy: SyncDocumentEnvelope,
        nowEpochMillis: Long,
    ): LegacyBootstrapMerge {
        val local = materializeLocalTombstones(
            base = stored?.acceptedBase,
            local = exported,
            nowEpochMillis = nowEpochMillis,
        )
        return when (
            val merged = merger.merge(
                base = stored?.acceptedBase,
                local = local,
                remote = legacy,
                mergeRevision = revisionSource.nextRevision(),
                mergedAtEpochMillis = nowEpochMillis,
            )
        ) {
            is SyncMergeResult.Rejected -> LegacyBootstrapMerge.Failure(merged.failure)
            is SyncMergeResult.Success -> {
                val outcome = merged.outcome
                if (outcome.hasConflicts) {
                    LegacyBootstrapMerge.Conflict(outcome.conflicts)
                } else {
                    LegacyBootstrapMerge.Success(
                        SyncDocumentEnvelope(
                            schemaVersion = outcome.schemaVersion,
                            kind = adapter.documentKind,
                            revision = revisionSource.nextRevision(),
                            generatedAtEpochMillis = nowEpochMillis,
                            records = outcome.records,
                        ),
                    )
                }
            }
        }
    }

    private fun materializeLocalTombstones(
        base: SyncDocumentEnvelope?,
        local: SyncDocumentEnvelope,
        nowEpochMillis: Long,
    ): SyncDocumentEnvelope {
        if (
            base == null ||
            base.kind != local.kind ||
            base.schemaVersion != local.schemaVersion
        ) {
            return local
        }

        val missingRecordIds = base.records.keys - local.records.keys
        if (missingRecordIds.isEmpty()) return local

        val records = local.records.toMutableMap()
        missingRecordIds.sorted().forEach { recordId ->
            val previous = base.records.getValue(recordId)
            records[recordId] = previous.copy(
                revision = revisionSource.nextRevision(),
                updatedAtEpochMillis = nowEpochMillis,
                deletedAtEpochMillis = nowEpochMillis,
            )
        }
        return local.copy(records = records)
    }

    private suspend fun recordConflict(
        kind: SyncDocumentKind,
        pending: SyncOutboxEntry?,
        conflicts: List<tachiyomi.domain.tsuzuki.sync.model.SyncConflict>,
        nowEpochMillis: Long,
    ): SyncDocumentResult.Conflict {
        conflictRepository.replaceForDocument(
            documentKind = kind,
            conflicts = conflicts,
            createdAtEpochMillis = nowEpochMillis,
        )
        if (pending == null) {
            outboxRepository.markDirty(
                documentKind = kind,
                enqueuedAtEpochMillis = nowEpochMillis,
            )
        }
        return SyncDocumentResult.Conflict(
            documentKind = kind,
            conflictCount = conflicts.size,
        )
    }

    private suspend fun failDocument(
        documentKind: SyncDocumentKind,
        pending: SyncOutboxEntry?,
        nowEpochMillis: Long,
        failure: SyncFailure,
    ): SyncDocumentResult.Failed {
        recordFailure(
            documentKind = documentKind,
            pending = pending,
            nowEpochMillis = nowEpochMillis,
            failure = failure,
        )
        return SyncDocumentResult.Failed(
            documentKind = documentKind,
            failure = failure,
        )
    }

    private suspend fun recordFailure(
        documentKind: SyncDocumentKind,
        pending: SyncOutboxEntry?,
        nowEpochMillis: Long,
        failure: SyncFailure,
    ) {
        if (pending == null) {
            outboxRepository.markDirty(
                documentKind = documentKind,
                enqueuedAtEpochMillis = nowEpochMillis,
            )
        }
        outboxRepository.recordFailure(
            documentKind = documentKind,
            nextAttemptAtEpochMillis = retryPolicy.nextAttemptAt(
                nowEpochMillis = nowEpochMillis,
                currentAttemptCount = pending?.attemptCount ?: 0,
                failure = failure,
            ),
        )
    }

    private fun SyncFailureReason.isCycleWideFailure(): Boolean {
        return this == SyncFailureReason.AUTHORIZATION_REQUIRED ||
            this == SyncFailureReason.NETWORK_UNAVAILABLE ||
            this == SyncFailureReason.REMOTE_UNAVAILABLE ||
            this == SyncFailureReason.RATE_LIMITED
    }

    private data class ReplicaDocument(
        val file: SyncRemoteFile,
        val journal: SyncReplicaJournal,
    )

    private sealed interface ReplicaWriteResult {
        data class Success(
            val file: SyncRemoteFile,
            val state: SyncReplicaState,
        ) : ReplicaWriteResult

        data class Failure(
            val failure: SyncFailure,
        ) : ReplicaWriteResult
    }

    private sealed interface LegacyBootstrapMerge {
        data class Success(
            val document: SyncDocumentEnvelope,
        ) : LegacyBootstrapMerge

        data class Conflict(
            val conflicts: List<tachiyomi.domain.tsuzuki.sync.model.SyncConflict>,
        ) : LegacyBootstrapMerge

        data class Failure(
            val failure: SyncFailure,
        ) : LegacyBootstrapMerge
    }

    private companion object {
        const val PROTOCOL_VERSION = 2
    }
}

private val SyncDocumentKind.syncApplyOrder: Int
    get() = when (this) {
        SyncDocumentKind.LIBRARY -> 0
        SyncDocumentKind.SOURCE_MAPPINGS -> 1
        SyncDocumentKind.COLLECTIONS -> 2
        SyncDocumentKind.CHAPTER_OVERRIDES -> 3
        SyncDocumentKind.SETTINGS -> 4
        SyncDocumentKind.FALLBACK_PROGRESS -> 5
        SyncDocumentKind.MANIFEST -> 6
    }
