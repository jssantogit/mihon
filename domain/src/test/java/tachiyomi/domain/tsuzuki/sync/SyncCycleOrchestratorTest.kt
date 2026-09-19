package tachiyomi.domain.tsuzuki.sync

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.sync.model.StoredSyncConflict
import tachiyomi.domain.tsuzuki.sync.model.SyncConflict
import tachiyomi.domain.tsuzuki.sync.model.SyncCycleReport
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentResult
import tachiyomi.domain.tsuzuki.sync.model.SyncFailure
import tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason
import tachiyomi.domain.tsuzuki.sync.model.SyncOutboxEntry
import tachiyomi.domain.tsuzuki.sync.model.SyncRecordEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncRemoteContent
import tachiyomi.domain.tsuzuki.sync.model.SyncRemoteFile
import tachiyomi.domain.tsuzuki.sync.model.SyncRemoteRevision
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision
import tachiyomi.domain.tsuzuki.sync.model.SyncStoredState
import tachiyomi.domain.tsuzuki.sync.model.SyncTransportResult
import tachiyomi.domain.tsuzuki.sync.repository.SyncConflictRepository
import tachiyomi.domain.tsuzuki.sync.repository.SyncOutboxRepository
import tachiyomi.domain.tsuzuki.sync.repository.SyncStateRepository
import tachiyomi.domain.tsuzuki.sync.service.DriveSyncTransport
import tachiyomi.domain.tsuzuki.sync.service.KotlinxSyncDocumentCodec
import tachiyomi.domain.tsuzuki.sync.service.SyncClock
import tachiyomi.domain.tsuzuki.sync.service.SyncCycleOrchestrator
import tachiyomi.domain.tsuzuki.sync.service.SyncDocumentAdapter
import tachiyomi.domain.tsuzuki.sync.service.SyncRevisionSource
import tachiyomi.domain.tsuzuki.sync.service.ThreeWaySyncMerger

class SyncCycleOrchestratorTest {

    private val codec = KotlinxSyncDocumentCodec(Json)

    @Test
    fun `case 1 - missing remote document creates it and accepts base`() = runTest {
        val local = document(record("a", "name" to "Local"))
        val adapter = FakeAdapter(SyncDocumentKind.LIBRARY, local)
        val transport = FakeTransport()
        val stores = Stores()

        val report = engine(transport, stores, adapter).runOnce()

        report.hasFailures.shouldBeFalse()
        transport.createdKinds shouldContainExactly listOf(SyncDocumentKind.LIBRARY)
        stores.state.get(SyncDocumentKind.LIBRARY)?.acceptedBase shouldBe local
        stores.outbox.get(SyncDocumentKind.LIBRARY) shouldBe null
    }

    @Test
    fun `case 2 - remote only change applies locally without rewriting Drive`() = runTest {
        val base = document(record("a", "name" to "Base"))
        val local = base
        val remote = document(record("a", "name" to "Remote", device = "tablet", sequence = 2))
        val remoteFile = remoteFile()
        val adapter = FakeAdapter(SyncDocumentKind.LIBRARY, local)
        val transport = FakeTransport(
            files = mutableListOf(remoteFile),
            contents = mutableMapOf(remoteFile.remoteId to encode(remote)),
        )
        val stores = Stores()
        stores.state.put(
            SyncStoredState(
                documentKind = SyncDocumentKind.LIBRARY,
                acceptedBase = base,
                remoteRevision = remoteFile.revision,
                lastSuccessfulSyncAtEpochMillis = 50,
            ),
        )

        val report = engine(transport, stores, adapter).runOnce()

        (report.documentResults.single() as SyncDocumentResult.Synchronized)
            .localApplied.shouldBeTrue()
        transport.updateCount shouldBe 0
        adapter.applied.single().records.getValue("a").fields["name"].toString() shouldBe "\"Remote\""
        stores.state.get(SyncDocumentKind.LIBRARY)?.acceptedBase shouldBe remote
    }

    @Test
    fun `case 3 - independent local and remote edits merge then update Drive`() = runTest {
        val base = document(
            record(
                "a",
                "name" to "Base",
                "status" to "reading",
            ),
        )
        val local = document(
            record(
                "a",
                "name" to "Phone",
                "status" to "reading",
                device = "phone",
                sequence = 2,
            ),
        )
        val remote = document(
            record(
                "a",
                "name" to "Base",
                "status" to "completed",
                device = "tablet",
                sequence = 2,
            ),
        )
        val remoteFile = remoteFile()
        val adapter = FakeAdapter(SyncDocumentKind.LIBRARY, local)
        val transport = FakeTransport(
            files = mutableListOf(remoteFile),
            contents = mutableMapOf(remoteFile.remoteId to encode(remote)),
        )
        val stores = Stores(
            outboxEntries = mutableMapOf(
                SyncDocumentKind.LIBRARY to SyncOutboxEntry(
                    documentKind = SyncDocumentKind.LIBRARY,
                    enqueuedAtEpochMillis = 90,
                ),
            ),
        )
        stores.state.put(
            SyncStoredState(
                documentKind = SyncDocumentKind.LIBRARY,
                acceptedBase = base,
                remoteRevision = remoteFile.revision,
                lastSuccessfulSyncAtEpochMillis = 50,
            ),
        )

        val report = engine(transport, stores, adapter).runOnce()
        val result = report.documentResults.single() as SyncDocumentResult.Synchronized

        result.localApplied.shouldBeTrue()
        result.remoteWritten.shouldBeTrue()
        transport.updateCount shouldBe 1
        val applied = adapter.applied.single().records.getValue("a").fields
        applied["name"].toString() shouldBe "\"Phone\""
        applied["status"].toString() shouldBe "\"completed\""
        stores.outbox.get(SyncDocumentKind.LIBRARY) shouldBe null
    }

    @Test
    fun `case 4 - same field conflict is persisted and never uploaded`() = runTest {
        val base = document(record("a", "name" to "Base"))
        val local = document(record("a", "name" to "Phone", device = "phone", sequence = 2))
        val remote = document(record("a", "name" to "Tablet", device = "tablet", sequence = 2))
        val remoteFile = remoteFile()
        val adapter = FakeAdapter(SyncDocumentKind.LIBRARY, local)
        val transport = FakeTransport(
            files = mutableListOf(remoteFile),
            contents = mutableMapOf(remoteFile.remoteId to encode(remote)),
        )
        val stores = Stores()
        stores.state.put(
            SyncStoredState(
                documentKind = SyncDocumentKind.LIBRARY,
                acceptedBase = base,
                remoteRevision = remoteFile.revision,
                lastSuccessfulSyncAtEpochMillis = 50,
            ),
        )

        val report = engine(transport, stores, adapter).runOnce()
        val result = report.documentResults.single() as SyncDocumentResult.Conflict

        result.conflictCount shouldBe 1
        transport.updateCount shouldBe 0
        stores.conflicts.getForDocument(SyncDocumentKind.LIBRARY).size shouldBe 1
        stores.state.get(SyncDocumentKind.LIBRARY)?.acceptedBase shouldBe base
        stores.outbox.get(SyncDocumentKind.LIBRARY) shouldBe
            SyncOutboxEntry(
                documentKind = SyncDocumentKind.LIBRARY,
                enqueuedAtEpochMillis = 100,
            )
    }

    @Test
    fun `case 5 - transport failure leaves local state and pending work intact`() = runTest {
        val local = document(record("a", "name" to "Local"))
        val adapter = FakeAdapter(SyncDocumentKind.LIBRARY, local)
        val transport = FakeTransport(
            createFailure = SyncFailure(SyncFailureReason.NETWORK_UNAVAILABLE),
        )
        val stores = Stores()

        val report = engine(transport, stores, adapter).runOnce()

        report.globalFailure?.reason shouldBe SyncFailureReason.NETWORK_UNAVAILABLE
        adapter.applied shouldBe emptyList()
        stores.state.get(SyncDocumentKind.LIBRARY) shouldBe null
        stores.outbox.get(SyncDocumentKind.LIBRARY)?.attemptCount shouldBe 1
    }

    @Test
    fun `case 6 - duplicate remote logical files fail closed`() = runTest {
        val local = document(record("a", "name" to "Local"))
        val adapter = FakeAdapter(SyncDocumentKind.LIBRARY, local)
        val transport = FakeTransport(
            files = mutableListOf(
                remoteFile(id = "one"),
                remoteFile(id = "two"),
            ),
        )
        val stores = Stores()

        val report = engine(transport, stores, adapter).runOnce()

        (report.documentResults.single() as SyncDocumentResult.Failed)
            .failure.reason shouldBe SyncFailureReason.MALFORMED_REMOTE_DOCUMENT
        transport.downloadCount shouldBe 0
    }

    @Test
    fun `case 7 - retry deadline defers document without touching transport content`() = runTest {
        val adapter = FakeAdapter(
            SyncDocumentKind.LIBRARY,
            document(record("a", "name" to "Local")),
        )
        val stores = Stores(
            outboxEntries = mutableMapOf(
                SyncDocumentKind.LIBRARY to SyncOutboxEntry(
                    documentKind = SyncDocumentKind.LIBRARY,
                    enqueuedAtEpochMillis = 50,
                    attemptCount = 1,
                    nextAttemptAtEpochMillis = 200,
                ),
            ),
        )
        val transport = FakeTransport()

        val report = engine(transport, stores, adapter).runOnce()

        report.documentResults.single() shouldBe
            SyncDocumentResult.Deferred(
                documentKind = SyncDocumentKind.LIBRARY,
                nextAttemptAtEpochMillis = 200,
            )
        transport.createCount shouldBe 0
        adapter.exportCount shouldBe 0
    }

    @Test
    fun `case 8 - cycle mutex serializes concurrent runs`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val transport = BlockingListTransport(entered, gate)
        val stores = Stores()
        val adapter = FakeAdapter(
            SyncDocumentKind.LIBRARY,
            document(record("a", "name" to "Local")),
        )
        val engine = engine(transport, stores, adapter)

        val first = async { engine.runOnce() }
        entered.await()
        val second = async { engine.runOnce() }

        transport.maxConcurrent shouldBe 1
        gate.complete(Unit)
        first.await()
        second.await()
        transport.maxConcurrent shouldBe 1
    }

    @Test
    fun `case 9 - fallback progress adapter is rejected until milestone 12`() {
        val adapter = FakeAdapter(
            SyncDocumentKind.FALLBACK_PROGRESS,
            document(
                record("a", "progress" to "1"),
                kind = SyncDocumentKind.FALLBACK_PROGRESS,
            ),
        )

        shouldThrow<IllegalArgumentException> {
            engine(FakeTransport(), Stores(), adapter)
        }
    }

    @Test
    fun `case 10 - remote change during update keeps accepted base unchanged`() = runTest {
        val base = document(record("a", "name" to "Base"))
        val local = document(record("a", "name" to "Local", device = "phone", sequence = 2))
        val remote = base
        val remoteFile = remoteFile()
        val adapter = FakeAdapter(SyncDocumentKind.LIBRARY, local)
        val transport = FakeTransport(
            files = mutableListOf(remoteFile),
            contents = mutableMapOf(remoteFile.remoteId to encode(remote)),
            updateFailure = SyncFailure(SyncFailureReason.REMOTE_CHANGED),
        )
        val stores = Stores()
        stores.state.put(
            SyncStoredState(
                documentKind = SyncDocumentKind.LIBRARY,
                acceptedBase = base,
                remoteRevision = remoteFile.revision,
                lastSuccessfulSyncAtEpochMillis = 50,
            ),
        )

        val report = engine(transport, stores, adapter).runOnce()

        (report.documentResults.single() as SyncDocumentResult.Failed)
            .failure.reason shouldBe SyncFailureReason.REMOTE_CHANGED
        stores.state.get(SyncDocumentKind.LIBRARY)?.acceptedBase shouldBe base
        stores.outbox.get(SyncDocumentKind.LIBRARY)?.attemptCount shouldBe 1
    }

    private fun engine(
        transport: DriveSyncTransport,
        stores: Stores,
        vararg adapters: SyncDocumentAdapter,
    ) = SyncCycleOrchestrator(
        transport = transport,
        outboxRepository = stores.outbox,
        stateRepository = stores.state,
        conflictRepository = stores.conflicts,
        adapters = adapters.toList(),
        codec = codec,
        merger = ThreeWaySyncMerger(),
        revisionSource = object : SyncRevisionSource {
            private var sequence = 10L

            override fun nextRevision() = SyncRevision(
                deviceId = "merge-device",
                sequence = sequence++,
            )
        },
        clock = object : SyncClock {
            override fun nowEpochMillis(): Long = 100
        },
    )

    private fun encode(document: SyncDocumentEnvelope): String {
        return when (val encoded = codec.encode(document)) {
            is tachiyomi.domain.tsuzuki.sync.model.SyncCodecResult.Success -> encoded.value
            is tachiyomi.domain.tsuzuki.sync.model.SyncCodecResult.Failure -> error("encode failed")
        }
    }

    private fun document(
        vararg records: SyncRecordEnvelope,
        kind: SyncDocumentKind = SyncDocumentKind.LIBRARY,
    ) = SyncDocumentEnvelope(
        schemaVersion = 1,
        kind = kind,
        revision = SyncRevision("document", 1),
        generatedAtEpochMillis = 10,
        records = records.associateBy { it.id },
    )

    private fun record(
        id: String,
        vararg fields: Pair<String, String>,
        device: String = "base",
        sequence: Long = 1,
    ) = SyncRecordEnvelope(
        id = id,
        revision = SyncRevision(device, sequence),
        updatedAtEpochMillis = sequence * 10,
        fields = buildJsonObject {
            fields.forEach { (key, value) -> put(key, value) }
        },
    )

    private fun remoteFile(
        id: String = "remote-library",
        version: String = "7",
    ) = SyncRemoteFile(
        remoteId = id,
        name = SyncDocumentKind.LIBRARY.fileName,
        mimeType = "application/json",
        revision = SyncRemoteRevision(
            remoteId = id,
            revisionToken = version,
        ),
    )

    private class FakeAdapter(
        override val documentKind: SyncDocumentKind,
        private var document: SyncDocumentEnvelope,
    ) : SyncDocumentAdapter {
        val applied = mutableListOf<SyncDocumentEnvelope>()
        var exportCount = 0

        override suspend fun exportDocument(): SyncDocumentEnvelope {
            exportCount += 1
            return document
        }

        override suspend fun applyDocument(document: SyncDocumentEnvelope) {
            applied += document
            this.document = document
        }
    }

    private class FakeTransport(
        val files: MutableList<SyncRemoteFile> = mutableListOf(),
        val contents: MutableMap<String, String> = mutableMapOf(),
        private val createFailure: SyncFailure? = null,
        private val updateFailure: SyncFailure? = null,
    ) : DriveSyncTransport {
        val createdKinds = mutableListOf<SyncDocumentKind>()
        var createCount = 0
        var updateCount = 0
        var downloadCount = 0

        override suspend fun listFiles(): SyncTransportResult<List<SyncRemoteFile>> {
            return SyncTransportResult.Success(files.toList())
        }

        override suspend fun download(file: SyncRemoteFile): SyncTransportResult<SyncRemoteContent> {
            downloadCount += 1
            val content = contents[file.remoteId]
                ?: return SyncTransportResult.Failure(
                    SyncFailure(SyncFailureReason.REMOTE_NOT_FOUND),
                )
            return SyncTransportResult.Success(
                SyncRemoteContent(
                    file = file,
                    content = content,
                ),
            )
        }

        override suspend fun create(
            documentKind: SyncDocumentKind,
            content: String,
        ): SyncTransportResult<SyncRemoteFile> {
            createCount += 1
            createFailure?.let { return SyncTransportResult.Failure(it) }
            createdKinds += documentKind
            val file = SyncRemoteFile(
                remoteId = "created-${documentKind.name}",
                name = documentKind.fileName,
                mimeType = "application/json",
                revision = SyncRemoteRevision(
                    remoteId = "created-${documentKind.name}",
                    revisionToken = "1",
                ),
            )
            files += file
            contents[file.remoteId] = content
            return SyncTransportResult.Success(file)
        }

        override suspend fun update(
            file: SyncRemoteFile,
            content: String,
        ): SyncTransportResult<SyncRemoteFile> {
            updateCount += 1
            updateFailure?.let { return SyncTransportResult.Failure(it) }
            val updated = file.copy(
                revision = SyncRemoteRevision(
                    remoteId = file.remoteId,
                    revisionToken = "8",
                ),
            )
            val index = files.indexOfFirst { it.remoteId == file.remoteId }
            if (index >= 0) files[index] = updated
            contents[file.remoteId] = content
            return SyncTransportResult.Success(updated)
        }
    }

    private class BlockingListTransport(
        private val entered: CompletableDeferred<Unit>,
        private val gate: CompletableDeferred<Unit>,
    ) : DriveSyncTransport {
        var active = 0
        var maxConcurrent = 0

        override suspend fun listFiles(): SyncTransportResult<List<SyncRemoteFile>> {
            active += 1
            maxConcurrent = maxOf(maxConcurrent, active)
            entered.complete(Unit)
            gate.await()
            active -= 1
            return SyncTransportResult.Success(emptyList())
        }

        override suspend fun download(file: SyncRemoteFile) =
            error("not used")

        override suspend fun create(
            documentKind: SyncDocumentKind,
            content: String,
        ): SyncTransportResult<SyncRemoteFile> {
            return SyncTransportResult.Success(
                SyncRemoteFile(
                    remoteId = "created-${documentKind.name}",
                    name = documentKind.fileName,
                    mimeType = "application/json",
                    revision = SyncRemoteRevision(
                        remoteId = "created-${documentKind.name}",
                        revisionToken = "1",
                    ),
                ),
            )
        }

        override suspend fun update(
            file: SyncRemoteFile,
            content: String,
        ) = error("not used")
    }

    private class Stores(
        outboxEntries: MutableMap<SyncDocumentKind, SyncOutboxEntry> = mutableMapOf(),
    ) {
        val outbox = InMemoryOutbox(outboxEntries)
        val state = InMemoryState()
        val conflicts = InMemoryConflicts()
    }

    private class InMemoryOutbox(
        private val entries: MutableMap<SyncDocumentKind, SyncOutboxEntry>,
    ) : SyncOutboxRepository {
        override suspend fun get(documentKind: SyncDocumentKind): SyncOutboxEntry? =
            entries[documentKind]

        override suspend fun markDirty(
            documentKind: SyncDocumentKind,
            enqueuedAtEpochMillis: Long,
        ) {
            entries[documentKind] = entries[documentKind]
                ?.markDirty(enqueuedAtEpochMillis)
                ?: SyncOutboxEntry(
                    documentKind = documentKind,
                    enqueuedAtEpochMillis = enqueuedAtEpochMillis,
                )
        }

        override suspend fun getPending(
            nowEpochMillis: Long,
            limit: Int,
        ): List<SyncOutboxEntry> {
            return entries.values
                .filter { it.isReady(nowEpochMillis) }
                .sortedBy { it.enqueuedAtEpochMillis }
                .take(limit)
        }

        override suspend fun recordFailure(
            documentKind: SyncDocumentKind,
            nextAttemptAtEpochMillis: Long?,
        ) {
            entries[documentKind] = checkNotNull(entries[documentKind])
                .recordFailure(nextAttemptAtEpochMillis)
        }

        override suspend fun clear(documentKind: SyncDocumentKind) {
            entries.remove(documentKind)
        }
    }

    private class InMemoryState : SyncStateRepository {
        private val states = mutableMapOf<SyncDocumentKind, SyncStoredState>()

        override suspend fun get(documentKind: SyncDocumentKind): SyncStoredState? =
            states[documentKind]

        override suspend fun put(state: SyncStoredState) {
            states[state.documentKind] = state
        }

        override suspend fun clear(documentKind: SyncDocumentKind) {
            states.remove(documentKind)
        }
    }

    private class InMemoryConflicts : SyncConflictRepository {
        private val conflicts = mutableMapOf<SyncDocumentKind, List<StoredSyncConflict>>()

        override suspend fun replaceForDocument(
            documentKind: SyncDocumentKind,
            conflicts: List<SyncConflict>,
            createdAtEpochMillis: Long,
        ) {
            this.conflicts[documentKind] = conflicts.map {
                StoredSyncConflict(
                    conflict = it,
                    createdAtEpochMillis = createdAtEpochMillis,
                )
            }
        }

        override suspend fun getForDocument(
            documentKind: SyncDocumentKind,
        ): List<StoredSyncConflict> = conflicts[documentKind].orEmpty()

        override suspend fun clearForDocument(documentKind: SyncDocumentKind) {
            conflicts.remove(documentKind)
        }
    }
}
