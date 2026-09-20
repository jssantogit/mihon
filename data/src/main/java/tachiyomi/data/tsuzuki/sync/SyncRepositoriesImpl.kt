package tachiyomi.data.tsuzuki.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.Json
import tachiyomi.data.Database
import tachiyomi.domain.tsuzuki.sync.model.StoredSyncConflict
import tachiyomi.domain.tsuzuki.sync.model.SyncCodecResult
import tachiyomi.domain.tsuzuki.sync.model.SyncConflict
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncFrontier
import tachiyomi.domain.tsuzuki.sync.model.SyncOutboxEntry
import tachiyomi.domain.tsuzuki.sync.model.SyncRemoteRevision
import tachiyomi.domain.tsuzuki.sync.model.SyncReplicaState
import tachiyomi.domain.tsuzuki.sync.model.SyncStoredState
import tachiyomi.domain.tsuzuki.sync.repository.SyncConflictRepository
import tachiyomi.domain.tsuzuki.sync.repository.SyncOutboxRepository
import tachiyomi.domain.tsuzuki.sync.repository.SyncReplicaRepository
import tachiyomi.domain.tsuzuki.sync.repository.SyncStateRepository
import tachiyomi.domain.tsuzuki.sync.service.KotlinxSyncDocumentCodec
import tachiyomi.domain.tsuzuki.sync.service.SyncDocumentCodec

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SyncOutboxRepositoryImpl(
    private val database: Database,
) : SyncOutboxRepository {

    override suspend fun get(documentKind: SyncDocumentKind): SyncOutboxEntry? {
        return database.tsuzuki_syncQueries
            .getTsuzukiSyncOutbox(documentKind.name, ::mapOutbox)
            .awaitAsOneOrNull()
    }

    override suspend fun markDirty(
        documentKind: SyncDocumentKind,
        enqueuedAtEpochMillis: Long,
    ) {
        require(documentKind != SyncDocumentKind.MANIFEST) {
            "Manifest is derived and cannot be enqueued"
        }

        database.transaction {
            val existing = database.tsuzuki_syncQueries
                .getTsuzukiSyncOutbox(documentKind.name, ::mapOutbox)
                .awaitAsOneOrNull()
            val entry = existing?.markDirty(enqueuedAtEpochMillis)
                ?: SyncOutboxEntry(
                    documentKind = documentKind,
                    enqueuedAtEpochMillis = enqueuedAtEpochMillis,
                )
            upsert(entry)
        }
    }

    override suspend fun getPending(
        nowEpochMillis: Long,
        limit: Int,
    ): List<SyncOutboxEntry> {
        require(nowEpochMillis >= 0) { "Sync outbox current time must not be negative" }
        require(limit > 0) { "Sync outbox limit must be positive" }

        return database.tsuzuki_syncQueries
            .getPendingTsuzukiSyncOutbox(
                nowEpochMillis = nowEpochMillis,
                limit = limit.toLong(),
                mapper = ::mapOutbox,
            )
            .awaitAsList()
    }

    override suspend fun recordFailure(
        documentKind: SyncDocumentKind,
        nextAttemptAtEpochMillis: Long?,
    ) {
        database.transaction {
            val existing = database.tsuzuki_syncQueries
                .getTsuzukiSyncOutbox(documentKind.name, ::mapOutbox)
                .awaitAsOneOrNull()
                ?: return@transaction
            upsert(existing.recordFailure(nextAttemptAtEpochMillis))
        }
    }

    override suspend fun clear(documentKind: SyncDocumentKind) {
        database.tsuzuki_syncQueries.deleteTsuzukiSyncOutbox(documentKind.name)
    }

    private suspend fun upsert(entry: SyncOutboxEntry) {
        database.tsuzuki_syncQueries.upsertTsuzukiSyncOutbox(
            documentKind = entry.documentKind.name,
            enqueuedAt = entry.enqueuedAtEpochMillis,
            attemptCount = entry.attemptCount.toLong(),
            nextAttemptAt = entry.nextAttemptAtEpochMillis,
        )
    }

    private fun mapOutbox(
        documentKind: String,
        enqueuedAt: Long,
        attemptCount: Long,
        nextAttemptAt: Long?,
    ) = SyncOutboxEntry(
        documentKind = SyncDocumentKind.valueOf(documentKind),
        enqueuedAtEpochMillis = enqueuedAt,
        attemptCount = attemptCount.toInt(),
        nextAttemptAtEpochMillis = nextAttemptAt,
    )
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SyncStateRepositoryImpl(
    private val database: Database,
) : SyncStateRepository {

    private val documentCodec: SyncDocumentCodec = KotlinxSyncDocumentCodec(SYNC_JSON)

    override suspend fun get(documentKind: SyncDocumentKind): SyncStoredState? {
        return database.tsuzuki_syncQueries
            .getTsuzukiSyncState(documentKind.name, ::mapState)
            .awaitAsOneOrNull()
    }

    override suspend fun put(state: SyncStoredState) {
        val remote = state.remoteRevision
        database.tsuzuki_syncQueries.upsertTsuzukiSyncState(
            documentKind = state.documentKind.name,
            acceptedBaseJson = state.acceptedBase?.let(::encodeDocument),
            remoteId = remote?.remoteId,
            remoteRevisionToken = remote?.revisionToken,
            remoteModifiedAt = remote?.modifiedAtEpochMillis,
            lastSuccessfulSyncAt = state.lastSuccessfulSyncAtEpochMillis,
            acceptedFrontierJson = encodeFrontier(state.acceptedFrontier),
        )
    }

    override suspend fun clear(documentKind: SyncDocumentKind) {
        database.tsuzuki_syncQueries.deleteTsuzukiSyncState(documentKind.name)
    }

    private fun mapState(
        documentKind: String,
        acceptedBaseJson: String?,
        remoteId: String?,
        remoteRevisionToken: String?,
        remoteModifiedAt: Long?,
        lastSuccessfulSyncAt: Long?,
        acceptedFrontierJson: String?,
    ): SyncStoredState {
        val kind = SyncDocumentKind.valueOf(documentKind)
        return SyncStoredState(
            documentKind = kind,
            acceptedBase = acceptedBaseJson?.let(::decodeDocument),
            remoteRevision = remoteId?.let {
                SyncRemoteRevision(
                    remoteId = it,
                    revisionToken = remoteRevisionToken,
                    modifiedAtEpochMillis = remoteModifiedAt,
                )
            },
            lastSuccessfulSyncAtEpochMillis = lastSuccessfulSyncAt,
            acceptedFrontier = acceptedFrontierJson?.let(::decodeFrontier) ?: SyncFrontier(),
        )
    }

    private fun encodeFrontier(frontier: SyncFrontier): String {
        return SYNC_JSON.encodeToString(
            SyncFrontier.serializer(),
            frontier.copy(entries = frontier.entries.toSortedMap()),
        )
    }

    private fun decodeFrontier(content: String): SyncFrontier {
        return SYNC_JSON.decodeFromString(SyncFrontier.serializer(), content)
    }

    private fun encodeDocument(document: SyncDocumentEnvelope): String {
        return when (val encoded = documentCodec.encode(document)) {
            is SyncCodecResult.Success -> encoded.value
            is SyncCodecResult.Failure -> error(
                "Unable to encode persisted sync base: ${encoded.failure.reason}",
            )
        }
    }

    private fun decodeDocument(content: String): SyncDocumentEnvelope {
        return when (val decoded = documentCodec.decode(content)) {
            is SyncCodecResult.Success -> decoded.value
            is SyncCodecResult.Failure -> error(
                "Persisted sync base is malformed: ${decoded.failure.reason}",
            )
        }
    }
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SyncReplicaRepositoryImpl(
    private val database: Database,
) : SyncReplicaRepository {

    override suspend fun get(
        documentKind: SyncDocumentKind,
        ownerDeviceId: String,
    ): SyncReplicaState? {
        require(ownerDeviceId.isNotBlank()) { "Sync replica owner device ID must not be blank" }
        return database.tsuzuki_syncQueries
            .getTsuzukiSyncReplica(
                documentKind = documentKind.name,
                ownerDeviceId = ownerDeviceId,
                mapper = ::mapReplica,
            )
            .awaitAsOneOrNull()
    }

    override suspend fun put(state: SyncReplicaState) {
        database.tsuzuki_syncQueries.upsertTsuzukiSyncReplica(
            documentKind = state.documentKind.name,
            ownerDeviceId = state.ownerDeviceId,
            reservedRemoteId = state.reservedRemoteId,
            lastRemoteRevisionToken = state.lastRemoteRevisionToken,
            nextSequence = state.nextSequence,
        )
    }

    override suspend fun clear(
        documentKind: SyncDocumentKind,
        ownerDeviceId: String,
    ) {
        require(ownerDeviceId.isNotBlank()) { "Sync replica owner device ID must not be blank" }
        database.tsuzuki_syncQueries.deleteTsuzukiSyncReplica(
            documentKind = documentKind.name,
            ownerDeviceId = ownerDeviceId,
        )
    }

    private fun mapReplica(
        documentKind: String,
        ownerDeviceId: String,
        reservedRemoteId: String?,
        lastRemoteRevisionToken: String?,
        nextSequence: Long,
    ) = SyncReplicaState(
        documentKind = SyncDocumentKind.valueOf(documentKind),
        ownerDeviceId = ownerDeviceId,
        reservedRemoteId = reservedRemoteId,
        lastRemoteRevisionToken = lastRemoteRevisionToken,
        nextSequence = nextSequence,
    )
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SyncConflictRepositoryImpl(
    private val database: Database,
) : SyncConflictRepository {

    override suspend fun replaceForDocument(
        documentKind: SyncDocumentKind,
        conflicts: List<SyncConflict>,
        createdAtEpochMillis: Long,
    ) {
        require(createdAtEpochMillis >= 0) { "Sync conflict creation time must not be negative" }
        require(conflicts.all { it.documentKind == documentKind }) {
            "All persisted conflicts must belong to the requested document"
        }

        database.transaction {
            database.tsuzuki_syncQueries.deleteTsuzukiSyncConflicts(documentKind.name)
            conflicts.forEachIndexed { index, conflict ->
                database.tsuzuki_syncQueries.insertTsuzukiSyncConflict(
                    documentKind = documentKind.name,
                    sortOrder = index.toLong(),
                    conflictJson = SYNC_JSON.encodeToString(
                        SyncConflict.serializer(),
                        conflict,
                    ),
                    createdAt = createdAtEpochMillis,
                )
            }
        }
    }

    override suspend fun getForDocument(
        documentKind: SyncDocumentKind,
    ): List<StoredSyncConflict> {
        return database.tsuzuki_syncQueries
            .getTsuzukiSyncConflicts(documentKind.name) {
                    storedDocumentKind,
                    _,
                    conflictJson,
                    createdAt,
                ->
                val conflict = SYNC_JSON.decodeFromString(
                    SyncConflict.serializer(),
                    conflictJson,
                )
                check(conflict.documentKind.name == storedDocumentKind) {
                    "Persisted sync conflict document kind does not match its row"
                }
                StoredSyncConflict(
                    conflict = conflict,
                    createdAtEpochMillis = createdAt,
                )
            }
            .awaitAsList()
    }

    override suspend fun clearForDocument(documentKind: SyncDocumentKind) {
        database.tsuzuki_syncQueries.deleteTsuzukiSyncConflicts(documentKind.name)
    }
}

private val SYNC_JSON = Json {
    encodeDefaults = true
}
