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
import tachiyomi.domain.tsuzuki.sync.model.SyncManifest
import tachiyomi.domain.tsuzuki.sync.model.SyncManifestEntry
import tachiyomi.domain.tsuzuki.sync.model.SyncMergeResult
import tachiyomi.domain.tsuzuki.sync.model.SyncOutboxEntry
import tachiyomi.domain.tsuzuki.sync.model.SyncRemoteFile
import tachiyomi.domain.tsuzuki.sync.model.SyncStoredState
import tachiyomi.domain.tsuzuki.sync.model.SyncTransportResult
import tachiyomi.domain.tsuzuki.sync.repository.SyncConflictRepository
import tachiyomi.domain.tsuzuki.sync.repository.SyncOutboxRepository
import tachiyomi.domain.tsuzuki.sync.repository.SyncStateRepository

class SyncCycleOrchestrator(
    private val transport: DriveSyncTransport,
    private val outboxRepository: SyncOutboxRepository,
    private val stateRepository: SyncStateRepository,
    private val conflictRepository: SyncConflictRepository,
    adapters: List<SyncDocumentAdapter>,
    private val codec: SyncDocumentCodec,
    private val manifestCodec: SyncManifestCodec,
    private val contentDigest: SyncContentDigest = Sha256SyncContentDigest(),
    private val merger: ThreeWaySyncMerger,
    private val revisionSource: SyncRevisionSource,
    private val clock: SyncClock,
    private val retryPolicy: SyncRetryPolicy = SyncRetryPolicy(),
) {
    private val mutex = Mutex()
    private val adapters = adapters.sortedBy { it.documentKind.name }

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

    suspend fun runOnce(): SyncCycleReport = mutex.withLock {
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

        val filesByName = remoteFiles.groupBy(SyncRemoteFile::name)
        val manifestFiles = filesByName[SyncDocumentKind.MANIFEST.fileName].orEmpty()
        if (manifestFiles.size > 1) {
            return SyncCycleReport(
                documentResults = emptyList(),
                globalFailure = SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT),
            )
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

            val matches = filesByName[adapter.documentKind.fileName].orEmpty()
            if (matches.size > 1) {
                val failure = SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT)
                recordFailure(
                    documentKind = adapter.documentKind,
                    pending = pending,
                    nowEpochMillis = now,
                    failure = failure,
                )
                results += SyncDocumentResult.Failed(
                    documentKind = adapter.documentKind,
                    failure = failure,
                )
                continue
            }

            val result = syncDocument(
                adapter = adapter,
                remoteFile = matches.singleOrNull(),
                pending = pending,
                nowEpochMillis = now,
            )
            results += result

            if (result is SyncDocumentResult.Failed &&
                result.failure.reason.isCycleWideFailure()
            ) {
                return SyncCycleReport(
                    documentResults = results,
                    globalFailure = result.failure,
                )
            }
        }

        val report = SyncCycleReport(documentResults = results)
        if (report.hasFailures ||
            report.hasConflicts ||
            results.any { it is SyncDocumentResult.Deferred }
        ) {
            return report
        }

        val manifestFailure = syncManifest(
            remoteFile = manifestFiles.singleOrNull(),
            nowEpochMillis = now,
        )
        return if (manifestFailure == null) {
            report
        } else {
            report.copy(globalFailure = manifestFailure)
        }
    }

    private suspend fun syncManifest(
        remoteFile: SyncRemoteFile?,
        nowEpochMillis: Long,
    ): SyncFailure? {
        return try {
            val existingManifest = if (remoteFile == null) {
                null
            } else {
                val content = when (val downloaded = transport.download(remoteFile)) {
                    is SyncTransportResult.Success -> downloaded.value.content
                    is SyncTransportResult.Failure -> return downloaded.failure
                }
                when (val decoded = manifestCodec.decode(content)) {
                    is SyncCodecResult.Success -> decoded.value
                    is SyncCodecResult.Failure -> return decoded.failure
                }
            }

            if (existingManifest != null && existingManifest.schemaVersion != MANIFEST_SCHEMA_VERSION) {
                return SyncFailure(SyncFailureReason.UNSUPPORTED_SCHEMA)
            }

            val documents = existingManifest?.documents
                ?.toMutableMap()
                ?: linkedMapOf()

            for (adapter in adapters) {
                val accepted = stateRepository.get(adapter.documentKind)
                    ?.acceptedBase
                    ?: return SyncFailure(SyncFailureReason.LOCAL_STATE_UNAVAILABLE)
                val encoded = when (val value = codec.encode(accepted)) {
                    is SyncCodecResult.Success -> value.value
                    is SyncCodecResult.Failure -> return value.failure
                }

                documents[adapter.documentKind] = SyncManifestEntry(
                    schemaVersion = accepted.schemaVersion,
                    revision = accepted.revision,
                    updatedAtEpochMillis = accepted.generatedAtEpochMillis,
                    contentDigest = contentDigest.digest(encoded),
                )
            }

            if (existingManifest?.documents == documents) {
                return null
            }

            val manifest = SyncManifest(
                schemaVersion = MANIFEST_SCHEMA_VERSION,
                revision = revisionSource.nextRevision(),
                updatedAtEpochMillis = nowEpochMillis,
                documents = documents,
            )
            val content = when (val encoded = manifestCodec.encode(manifest)) {
                is SyncCodecResult.Success -> encoded.value
                is SyncCodecResult.Failure -> return encoded.failure
            }

            val result = if (remoteFile == null) {
                transport.create(
                    documentKind = SyncDocumentKind.MANIFEST,
                    content = content,
                )
            } else {
                transport.update(
                    file = remoteFile,
                    content = content,
                )
            }

            when (result) {
                is SyncTransportResult.Success -> null
                is SyncTransportResult.Failure -> result.failure
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            SyncFailure(SyncFailureReason.LOCAL_STATE_UNAVAILABLE)
        }
    }

    private suspend fun syncDocument(
        adapter: SyncDocumentAdapter,
        remoteFile: SyncRemoteFile?,
        pending: SyncOutboxEntry?,
        nowEpochMillis: Long,
    ): SyncDocumentResult {
        return try {
            val storedState = stateRepository.get(adapter.documentKind)
            val exportedLocalDocument = adapter.exportDocument()
            if (exportedLocalDocument.kind != adapter.documentKind) {
                return failDocument(
                    documentKind = adapter.documentKind,
                    pending = pending,
                    nowEpochMillis = nowEpochMillis,
                    failure = SyncFailure(SyncFailureReason.MALFORMED_DOCUMENT),
                )
            }

            val localDocument = materializeLocalTombstones(
                base = storedState?.acceptedBase,
                local = exportedLocalDocument,
                nowEpochMillis = nowEpochMillis,
            )

            if (remoteFile == null) {
                return createRemote(
                    adapter = adapter,
                    localDocument = localDocument,
                    pending = pending,
                    nowEpochMillis = nowEpochMillis,
                )
            }

            val remoteContent = when (val downloaded = transport.download(remoteFile)) {
                is SyncTransportResult.Success -> downloaded.value.content
                is SyncTransportResult.Failure -> {
                    return failDocument(
                        documentKind = adapter.documentKind,
                        pending = pending,
                        nowEpochMillis = nowEpochMillis,
                        failure = downloaded.failure,
                    )
                }
            }

            val remoteDocument = when (val decoded = codec.decode(remoteContent)) {
                is SyncCodecResult.Success -> decoded.value
                is SyncCodecResult.Failure -> {
                    return failDocument(
                        documentKind = adapter.documentKind,
                        pending = pending,
                        nowEpochMillis = nowEpochMillis,
                        failure = decoded.failure,
                    )
                }
            }

            val mergeRevision = revisionSource.nextRevision()
            val mergeResult = merger.merge(
                base = storedState?.acceptedBase,
                local = localDocument,
                remote = remoteDocument,
                mergeRevision = mergeRevision,
                mergedAtEpochMillis = nowEpochMillis,
            )

            when (mergeResult) {
                is SyncMergeResult.Rejected -> failDocument(
                    documentKind = adapter.documentKind,
                    pending = pending,
                    nowEpochMillis = nowEpochMillis,
                    failure = mergeResult.failure,
                )

                is SyncMergeResult.Success -> {
                    val outcome = mergeResult.outcome
                    val mergedDocument = SyncDocumentEnvelope(
                        schemaVersion = outcome.schemaVersion,
                        kind = outcome.documentKind,
                        revision = mergeRevision,
                        generatedAtEpochMillis = nowEpochMillis,
                        records = outcome.records,
                    )

                    if (outcome.requiresLocalApply) {
                        adapter.applyDocument(mergedDocument)
                    }

                    if (outcome.hasConflicts) {
                        conflictRepository.replaceForDocument(
                            documentKind = adapter.documentKind,
                            conflicts = outcome.conflicts,
                            createdAtEpochMillis = nowEpochMillis,
                        )
                        if (pending == null) {
                            outboxRepository.markDirty(
                                documentKind = adapter.documentKind,
                                enqueuedAtEpochMillis = nowEpochMillis,
                            )
                        }
                        return SyncDocumentResult.Conflict(
                            documentKind = adapter.documentKind,
                            conflictCount = outcome.conflicts.size,
                        )
                    }

                    val acceptedFile = if (outcome.requiresRemoteWrite) {
                        val encoded = when (val value = codec.encode(mergedDocument)) {
                            is SyncCodecResult.Success -> value.value
                            is SyncCodecResult.Failure -> {
                                return failDocument(
                                    documentKind = adapter.documentKind,
                                    pending = pending,
                                    nowEpochMillis = nowEpochMillis,
                                    failure = value.failure,
                                )
                            }
                        }

                        when (val updated = transport.update(remoteFile, encoded)) {
                            is SyncTransportResult.Success -> updated.value
                            is SyncTransportResult.Failure -> {
                                return failDocument(
                                    documentKind = adapter.documentKind,
                                    pending = pending,
                                    nowEpochMillis = nowEpochMillis,
                                    failure = updated.failure,
                                )
                            }
                        }
                    } else {
                        remoteFile
                    }

                    val acceptedBase = if (outcome.requiresRemoteWrite) {
                        mergedDocument
                    } else {
                        remoteDocument
                    }

                    acceptDocument(
                        documentKind = adapter.documentKind,
                        acceptedBase = acceptedBase,
                        remoteFile = acceptedFile,
                        nowEpochMillis = nowEpochMillis,
                    )

                    SyncDocumentResult.Synchronized(
                        documentKind = adapter.documentKind,
                        localApplied = outcome.requiresLocalApply,
                        remoteWritten = outcome.requiresRemoteWrite,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            failDocument(
                documentKind = adapter.documentKind,
                pending = pending,
                nowEpochMillis = nowEpochMillis,
                failure = SyncFailure(SyncFailureReason.LOCAL_STATE_UNAVAILABLE),
            )
        }
    }

    private fun materializeLocalTombstones(
        base: SyncDocumentEnvelope?,
        local: SyncDocumentEnvelope,
        nowEpochMillis: Long,
    ): SyncDocumentEnvelope {
        if (base == null ||
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

    private suspend fun createRemote(
        adapter: SyncDocumentAdapter,
        localDocument: SyncDocumentEnvelope,
        pending: SyncOutboxEntry?,
        nowEpochMillis: Long,
    ): SyncDocumentResult {
        val encoded = when (val value = codec.encode(localDocument)) {
            is SyncCodecResult.Success -> value.value
            is SyncCodecResult.Failure -> {
                return failDocument(
                    documentKind = adapter.documentKind,
                    pending = pending,
                    nowEpochMillis = nowEpochMillis,
                    failure = value.failure,
                )
            }
        }

        val created = when (
            val result = transport.create(
                documentKind = adapter.documentKind,
                content = encoded,
            )
        ) {
            is SyncTransportResult.Success -> result.value
            is SyncTransportResult.Failure -> {
                return failDocument(
                    documentKind = adapter.documentKind,
                    pending = pending,
                    nowEpochMillis = nowEpochMillis,
                    failure = result.failure,
                )
            }
        }

        acceptDocument(
            documentKind = adapter.documentKind,
            acceptedBase = localDocument,
            remoteFile = created,
            nowEpochMillis = nowEpochMillis,
        )

        return SyncDocumentResult.Synchronized(
            documentKind = adapter.documentKind,
            localApplied = false,
            remoteWritten = true,
        )
    }

    private suspend fun acceptDocument(
        documentKind: SyncDocumentKind,
        acceptedBase: SyncDocumentEnvelope,
        remoteFile: SyncRemoteFile,
        nowEpochMillis: Long,
    ) {
        stateRepository.put(
            SyncStoredState(
                documentKind = documentKind,
                acceptedBase = acceptedBase,
                remoteRevision = remoteFile.revision,
                lastSuccessfulSyncAtEpochMillis = nowEpochMillis,
            ),
        )
        conflictRepository.clearForDocument(documentKind)
        outboxRepository.clear(documentKind)
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

    private companion object {
        const val MANIFEST_SCHEMA_VERSION = 1
    }
}
