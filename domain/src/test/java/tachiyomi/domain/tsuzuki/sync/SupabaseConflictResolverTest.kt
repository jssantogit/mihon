package tachiyomi.domain.tsuzuki.sync

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.account.model.AccountState
import tachiyomi.domain.tsuzuki.account.model.TsuzukiAccount
import tachiyomi.domain.tsuzuki.account.repository.AccountRepository
import tachiyomi.domain.tsuzuki.sync.model.StoredSyncConflict
import tachiyomi.domain.tsuzuki.sync.model.SupabaseMutationBatch
import tachiyomi.domain.tsuzuki.sync.model.SupabasePendingMutation
import tachiyomi.domain.tsuzuki.sync.model.SupabasePushResult
import tachiyomi.domain.tsuzuki.sync.model.SupabaseSyncCursor
import tachiyomi.domain.tsuzuki.sync.model.SupabaseSyncEvent
import tachiyomi.domain.tsuzuki.sync.model.SupabaseSyncSnapshot
import tachiyomi.domain.tsuzuki.sync.model.SupabaseSyncSnapshotRecord
import tachiyomi.domain.tsuzuki.sync.model.SyncConflict
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictKind
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictResolutionChoice
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictResolutionResult
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictValue
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncFailure
import tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason
import tachiyomi.domain.tsuzuki.sync.model.SyncOutboxEntry
import tachiyomi.domain.tsuzuki.sync.model.SyncRecordEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision
import tachiyomi.domain.tsuzuki.sync.model.SyncStoredState
import tachiyomi.domain.tsuzuki.sync.model.SyncTransportResult
import tachiyomi.domain.tsuzuki.sync.repository.SyncConflictRepository
import tachiyomi.domain.tsuzuki.sync.repository.SyncOutboxRepository
import tachiyomi.domain.tsuzuki.sync.repository.SyncStateRepository
import tachiyomi.domain.tsuzuki.sync.service.SupabaseConflictResolver
import tachiyomi.domain.tsuzuki.sync.service.SupabaseSyncStateStore
import tachiyomi.domain.tsuzuki.sync.service.SupabaseSyncTransport
import tachiyomi.domain.tsuzuki.sync.service.SyncClientIdentityProvider
import tachiyomi.domain.tsuzuki.sync.service.SyncClock
import tachiyomi.domain.tsuzuki.sync.service.SyncDocumentAdapter

class SupabaseConflictResolverTest {

    @Test
    fun `keep remote changes only conflicted field and acknowledges remote conflict`() = runTest {
        val adapter = FakeAdapter(
            document(
                status = "READING",
                note = "keep-me",
            ),
        )
        val conflict = conflict(remoteConflictId = 42)
        val conflicts = FakeConflictRepository().apply {
            replaceForDocument(
                documentKind = SyncDocumentKind.LIBRARY,
                conflicts = listOf(conflict),
                createdAtEpochMillis = 1,
            )
        }
        val outbox = FakeOutbox().apply {
            markDirty(SyncDocumentKind.LIBRARY, 1)
        }
        val transport = FakeTransport(
            snapshotValue = snapshot(
                cursor = 5,
                status = "COMPLETED",
                note = "keep-me",
            ),
        )
        val resolver = resolver(
            adapter = adapter,
            transport = transport,
            conflicts = conflicts,
            outbox = outbox,
        )

        val result = resolver.resolve(
            conflict = conflict,
            choice = SyncConflictResolutionChoice.KEEP_REMOTE,
        )

        result shouldBe SyncConflictResolutionResult.Resolved
        adapter.field("status") shouldBe JsonPrimitive("COMPLETED")
        adapter.field("note") shouldBe JsonPrimitive("keep-me")
        transport.acknowledgedIds shouldBe listOf(42L)
        conflicts.getForDocument(SyncDocumentKind.LIBRARY) shouldBe emptyList()
        outbox.get(SyncDocumentKind.LIBRARY) shouldBe null
    }

    @Test
    fun `keep remote reconciles dirty intent before acknowledgement and retains conflict on ack failure`() = runTest {
        val adapter = FakeAdapter(document(status = "READING"))
        val conflict = conflict(remoteConflictId = 66)
        val conflicts = FakeConflictRepository().apply {
            replaceForDocument(
                documentKind = SyncDocumentKind.LIBRARY,
                conflicts = listOf(conflict),
                createdAtEpochMillis = 1,
            )
        }
        val outbox = FakeOutbox().apply {
            markDirty(SyncDocumentKind.LIBRARY, 1)
        }
        val transport = FakeTransport(
            snapshotValue = snapshot(
                cursor = 6,
                status = "COMPLETED",
                note = null,
            ),
            ackResult = SyncTransportResult.Failure(
                SyncFailure(SyncFailureReason.NETWORK_UNAVAILABLE),
            ),
        )
        val resolver = resolver(
            adapter = adapter,
            transport = transport,
            conflicts = conflicts,
            outbox = outbox,
        )

        val result = resolver.resolve(
            conflict = conflict,
            choice = SyncConflictResolutionChoice.KEEP_REMOTE,
        )

        (result as SyncConflictResolutionResult.Failed).failure.reason shouldBe
            SyncFailureReason.NETWORK_UNAVAILABLE
        adapter.field("status") shouldBe JsonPrimitive("COMPLETED")
        outbox.get(SyncDocumentKind.LIBRARY) shouldBe null
        conflicts.getForDocument(SyncDocumentKind.LIBRARY).map { it.conflict } shouldBe
            listOf(conflict)
    }

    @Test
    fun `keep local pushes causally later field mutation before acknowledgement`() = runTest {
        val adapter = FakeAdapter(document(status = "READING"))
        val conflict = conflict(remoteConflictId = 77)
        val conflicts = FakeConflictRepository().apply {
            replaceForDocument(
                documentKind = SyncDocumentKind.LIBRARY,
                conflicts = listOf(conflict),
                createdAtEpochMillis = 1,
            )
        }
        val outbox = FakeOutbox().apply {
            markDirty(SyncDocumentKind.LIBRARY, 1)
        }
        val cloudState = FakeCloudState()
        val transport = FakeTransport(
            snapshotValue = snapshot(
                cursor = 9,
                status = "COMPLETED",
                note = null,
            ),
        )
        val resolver = resolver(
            adapter = adapter,
            transport = transport,
            conflicts = conflicts,
            outbox = outbox,
            cloudState = cloudState,
        )

        val result = resolver.resolve(
            conflict = conflict,
            choice = SyncConflictResolutionChoice.KEEP_LOCAL,
        )

        result shouldBe SyncConflictResolutionResult.Resolved
        transport.pushedBatches.size shouldBe 1
        transport.pushedBatches.single().baseCursor shouldBe 9
        transport.pushedBatches.single().operations.single().let { mutation ->
            mutation.recordId shouldBe "title-1"
        }
        transport.callOrder shouldBe listOf("snapshot", "push", "ack:77")
        cloudState.getPending(SyncDocumentKind.LIBRARY) shouldBe null
        conflicts.getForDocument(SyncDocumentKind.LIBRARY) shouldBe emptyList()
        outbox.get(SyncDocumentKind.LIBRARY) shouldNotBe null
    }

    @Test
    fun `keep local retains exact pending mutation when acknowledgement response is unavailable`() = runTest {
        val adapter = FakeAdapter(document(status = "READING"))
        val conflict = conflict(remoteConflictId = 88)
        val conflicts = FakeConflictRepository().apply {
            replaceForDocument(
                documentKind = SyncDocumentKind.LIBRARY,
                conflicts = listOf(conflict),
                createdAtEpochMillis = 1,
            )
        }
        val cloudState = FakeCloudState()
        val transport = FakeTransport(
            snapshotValue = snapshot(
                cursor = 11,
                status = "COMPLETED",
                note = null,
            ),
            ackResult = SyncTransportResult.Failure(
                SyncFailure(SyncFailureReason.NETWORK_UNAVAILABLE),
            ),
        )
        val resolver = resolver(
            adapter = adapter,
            transport = transport,
            conflicts = conflicts,
            cloudState = cloudState,
        )

        val first = resolver.resolve(
            conflict = conflict,
            choice = SyncConflictResolutionChoice.KEEP_LOCAL,
        )

        (first as SyncConflictResolutionResult.Failed).failure.reason shouldBe
            SyncFailureReason.NETWORK_UNAVAILABLE
        val pendingAfterFirst = cloudState.getPending(SyncDocumentKind.LIBRARY)
        pendingAfterFirst shouldNotBe null

        transport.ackResult = SyncTransportResult.Success(true)
        val second = resolver.resolve(
            conflict = conflict,
            choice = SyncConflictResolutionChoice.KEEP_LOCAL,
        )

        second shouldBe SyncConflictResolutionResult.Resolved
        transport.pushedBatches.map { it.mutationId }.toSet().size shouldBe 1
        cloudState.getPending(SyncDocumentKind.LIBRARY) shouldBe null
    }

    private fun resolver(
        adapter: FakeAdapter,
        transport: FakeTransport,
        conflicts: FakeConflictRepository,
        outbox: FakeOutbox = FakeOutbox(),
        cloudState: FakeCloudState = FakeCloudState(),
    ): SupabaseConflictResolver {
        var mutationSequence = 0
        return SupabaseConflictResolver(
            accountRepository = FakeAccountRepository(),
            transport = transport,
            outboxRepository = outbox,
            stateRepository = FakeStoredState(),
            supabaseStateStore = cloudState,
            conflictRepository = conflicts,
            adapters = listOf(adapter),
            clientIdentityProvider = SyncClientIdentityProvider { "device-1" },
            clock = object : SyncClock {
                private var now = 100L
                override fun nowEpochMillis(): Long = now++
            },
            mutationIdSource = {
                mutationSequence += 1
                "20000000-0000-0000-0000-${mutationSequence.toString().padStart(12, '0')}"
            },
        )
    }

    private fun conflict(remoteConflictId: Long) = SyncConflict(
        documentKind = SyncDocumentKind.LIBRARY,
        recordId = "title-1",
        propertyPath = listOf("status"),
        kind = SyncConflictKind.FIELD_DIVERGENCE,
        base = SyncConflictValue.Present(JsonPrimitive("PLANNING")),
        local = SyncConflictValue.Present(JsonPrimitive("READING")),
        remote = SyncConflictValue.Present(JsonPrimitive("COMPLETED")),
        remoteConflictId = remoteConflictId,
    )

    private fun document(
        status: String,
        note: String? = null,
    ): SyncDocumentEnvelope {
        val fields = buildJsonObject {
            put("status", JsonPrimitive(status))
            note?.let { put("note", JsonPrimitive(it)) }
        }
        return SyncDocumentEnvelope(
            schemaVersion = 1,
            kind = SyncDocumentKind.LIBRARY,
            revision = SyncRevision("local", 1),
            generatedAtEpochMillis = 1,
            records = mapOf(
                "title-1" to SyncRecordEnvelope(
                    id = "title-1",
                    revision = SyncRevision("local", 1),
                    updatedAtEpochMillis = 1,
                    fields = fields,
                ),
            ),
        )
    }

    private fun snapshot(
        cursor: Long,
        status: String,
        note: String?,
    ): SupabaseSyncSnapshot {
        val fields = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
            "status" to JsonPrimitive(status),
        )
        note?.let { fields["note"] = JsonPrimitive(it) }
        return SupabaseSyncSnapshot(
            documentKind = SyncDocumentKind.LIBRARY,
            cursor = cursor,
            records = listOf(
                SupabaseSyncSnapshotRecord(
                    recordId = "title-1",
                    isDeleted = false,
                    fields = JsonObject(fields),
                ),
            ),
        )
    }

    private class FakeAdapter(
        var current: SyncDocumentEnvelope,
    ) : SyncDocumentAdapter {
        override val documentKind = SyncDocumentKind.LIBRARY

        override suspend fun exportDocument(): SyncDocumentEnvelope = current

        override suspend fun applyDocument(document: SyncDocumentEnvelope) {
            current = document
        }

        fun field(name: String) = current.records["title-1"]?.fields?.get(name)
    }

    private class FakeAccountRepository : AccountRepository {
        private val mutable = MutableStateFlow<AccountState>(
            AccountState.Authenticated(
                TsuzukiAccount("user-1", "reader@example.com"),
            ),
        )
        override val state: StateFlow<AccountState> = mutable

        override suspend fun register(email: String, password: String) =
            error("not used")

        override suspend fun login(email: String, password: String) =
            error("not used")

        override suspend fun logout() = error("not used")

        override suspend fun sendPasswordRecovery(email: String) =
            error("not used")

        override suspend fun refreshSession() = error("not used")

        override suspend fun getAccessToken() = Result.success("token")
    }

    private class FakeTransport(
        private val snapshotValue: SupabaseSyncSnapshot,
        var ackResult: SyncTransportResult<Boolean> = SyncTransportResult.Success(true),
    ) : SupabaseSyncTransport {
        val pushedBatches = mutableListOf<SupabaseMutationBatch>()
        val acknowledgedIds = mutableListOf<Long>()
        val callOrder = mutableListOf<String>()

        override suspend fun pullDelta(
            documentKind: SyncDocumentKind,
            sinceEventId: Long,
            limit: Int,
        ): SyncTransportResult<List<SupabaseSyncEvent>> =
            SyncTransportResult.Success(emptyList())

        override suspend fun snapshot(
            documentKind: SyncDocumentKind,
        ): SyncTransportResult<SupabaseSyncSnapshot> {
            callOrder += "snapshot"
            return SyncTransportResult.Success(snapshotValue)
        }

        override suspend fun push(
            batch: SupabaseMutationBatch,
        ): SyncTransportResult<SupabasePushResult> {
            callOrder += "push"
            pushedBatches += batch
            return SyncTransportResult.Success(
                SupabasePushResult(
                    cursor = snapshotValue.cursor + 1,
                    conflicts = emptyList(),
                ),
            )
        }

        override suspend fun ackConflict(
            conflictId: Long,
        ): SyncTransportResult<Boolean> {
            callOrder += "ack:$conflictId"
            acknowledgedIds += conflictId
            return ackResult
        }
    }

    private class FakeOutbox : SyncOutboxRepository {
        private val values = mutableMapOf<SyncDocumentKind, SyncOutboxEntry>()

        override suspend fun get(documentKind: SyncDocumentKind) = values[documentKind]

        override suspend fun markDirty(
            documentKind: SyncDocumentKind,
            enqueuedAtEpochMillis: Long,
        ) {
            values[documentKind] = values[documentKind]
                ?.markDirty(enqueuedAtEpochMillis)
                ?: SyncOutboxEntry(documentKind, enqueuedAtEpochMillis)
        }

        override suspend fun getPending(
            nowEpochMillis: Long,
            limit: Int,
        ): List<SyncOutboxEntry> =
            values.values.filter { it.isReady(nowEpochMillis) }.take(limit)

        override suspend fun recordFailure(
            documentKind: SyncDocumentKind,
            nextAttemptAtEpochMillis: Long?,
        ) {
            val existing = values[documentKind] ?: return
            values[documentKind] = existing.recordFailure(nextAttemptAtEpochMillis)
        }

        override suspend fun clear(documentKind: SyncDocumentKind) {
            values.remove(documentKind)
        }
    }

    private class FakeStoredState : SyncStateRepository {
        private val values = mutableMapOf<SyncDocumentKind, SyncStoredState>()

        override suspend fun get(documentKind: SyncDocumentKind) = values[documentKind]

        override suspend fun put(state: SyncStoredState) {
            values[state.documentKind] = state
        }

        override suspend fun clear(documentKind: SyncDocumentKind) {
            values.remove(documentKind)
        }
    }

    private class FakeCloudState : SupabaseSyncStateStore {
        private val cursors = mutableMapOf<SyncDocumentKind, SupabaseSyncCursor>()
        private val pending = mutableMapOf<SyncDocumentKind, SupabasePendingMutation>()

        override suspend fun getCursor(documentKind: SyncDocumentKind) =
            cursors[documentKind]

        override suspend fun putCursor(
            documentKind: SyncDocumentKind,
            eventCursor: Long,
            lastSuccessfulSyncAtEpochMillis: Long?,
        ) {
            cursors[documentKind] = SupabaseSyncCursor(
                documentKind = documentKind,
                eventCursor = eventCursor,
                lastSuccessfulSyncAtEpochMillis = lastSuccessfulSyncAtEpochMillis,
            )
        }

        override suspend fun getPending(documentKind: SyncDocumentKind) =
            pending[documentKind]

        override suspend fun putPending(mutation: SupabasePendingMutation) {
            pending[mutation.batch.documentKind] = mutation
        }

        override suspend fun recordPendingFailure(
            mutation: SupabasePendingMutation,
            nextAttemptAtEpochMillis: Long?,
        ) {
            pending[mutation.batch.documentKind] = mutation.copy(
                attemptCount = mutation.attemptCount + 1,
                nextAttemptAtEpochMillis = nextAttemptAtEpochMillis,
            )
        }

        override suspend fun deletePending(mutationId: String) {
            pending.entries.removeAll { it.value.batch.mutationId == mutationId }
        }
    }

    private class FakeConflictRepository : SyncConflictRepository {
        private val values = mutableMapOf<SyncDocumentKind, List<StoredSyncConflict>>()

        override suspend fun replaceForDocument(
            documentKind: SyncDocumentKind,
            conflicts: List<SyncConflict>,
            createdAtEpochMillis: Long,
        ) {
            values[documentKind] = conflicts.map {
                StoredSyncConflict(
                    conflict = it,
                    createdAtEpochMillis = createdAtEpochMillis,
                )
            }
        }

        override suspend fun getForDocument(
            documentKind: SyncDocumentKind,
        ): List<StoredSyncConflict> = values[documentKind].orEmpty()

        override suspend fun clearForDocument(documentKind: SyncDocumentKind) {
            values.remove(documentKind)
        }
    }
}
