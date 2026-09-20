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
import tachiyomi.domain.tsuzuki.sync.model.SyncReplicaState
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision
import tachiyomi.domain.tsuzuki.sync.model.SyncStoredState
import tachiyomi.domain.tsuzuki.sync.model.SyncTransportResult
import tachiyomi.domain.tsuzuki.sync.repository.SyncConflictRepository
import tachiyomi.domain.tsuzuki.sync.repository.SyncOutboxRepository
import tachiyomi.domain.tsuzuki.sync.repository.SyncReplicaRepository
import tachiyomi.domain.tsuzuki.sync.repository.SyncStateRepository
import tachiyomi.domain.tsuzuki.sync.service.DriveSyncTransport
import tachiyomi.domain.tsuzuki.sync.service.KotlinxSyncDocumentCodec
import tachiyomi.domain.tsuzuki.sync.service.SyncClock
import tachiyomi.domain.tsuzuki.sync.service.SyncCycleOrchestrator
import tachiyomi.domain.tsuzuki.sync.service.SyncDocumentAdapter
import tachiyomi.domain.tsuzuki.sync.service.SyncDocumentDiffer
import tachiyomi.domain.tsuzuki.sync.service.SyncReplicaBootstrap
import tachiyomi.domain.tsuzuki.sync.service.SyncReplicaJournalCodec
import tachiyomi.domain.tsuzuki.sync.service.SyncReplicaMaterializer
import tachiyomi.domain.tsuzuki.sync.service.SyncRevisionSource
import tachiyomi.domain.tsuzuki.sync.service.ThreeWaySyncMerger

class SyncCycleOrchestratorTest {

    private val codec = KotlinxSyncDocumentCodec(Json)
    private val journalCodec = SyncReplicaJournalCodec(Json)
    private val differ = SyncDocumentDiffer()
    private val materializer = SyncReplicaMaterializer()
    private val bootstrap = SyncReplicaBootstrap(codec)

    @Test
    fun `case 1 - missing remote document creates it and accepts base`() = runTest {
        val local = document(record("a", "name" to "Local"))
        val adapter = FakeAdapter(SyncDocumentKind.LIBRARY, local)
        val transport = FakeTransport()
        val stores = Stores()

        val report = engine(transport, stores, adapter).runOnce()

        report.hasFailures.shouldBeFalse()
        val replicaFile = transport.files.single {
            it.protocolVersion == 2 &&
                it.logicalKind == SyncDocumentKind.LIBRARY &&
                it.ownerDeviceId == "merge-device"
        }
        transport.createdKinds shouldBe emptyList()
        transport.contents.containsKey(replicaFile.remoteId).shouldBeTrue()
        stores.state.get(SyncDocumentKind.LIBRARY)
            ?.acceptedBase
            ?.records
            ?.getValue("a")
            ?.fields shouldBe local.records.getValue("a").fields
        stores.outbox.get(SyncDocumentKind.LIBRARY) shouldBe null
    }

    @Test
    fun `case 2 - remote only legacy change applies locally without mutating legacy file`() = runTest {
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

        val result = report.documentResults.single() as SyncDocumentResult.Synchronized
        result.localApplied.shouldBeTrue()
        result.remoteWritten.shouldBeTrue()
        transport.updateCount shouldBe 0
        transport.contents.getValue(remoteFile.remoteId) shouldBe encode(remote)
        transport.files.count {
            it.protocolVersion == 2 &&
                it.logicalKind == SyncDocumentKind.LIBRARY &&
                it.ownerDeviceId == "merge-device"
        } shouldBe 1
        adapter.applied.single().records.getValue("a").fields["name"].toString() shouldBe "\"Remote\""
        stores.state.get(SyncDocumentKind.LIBRARY)
            ?.acceptedBase
            ?.records
            ?.getValue("a")
            ?.fields shouldBe remote.records.getValue("a").fields
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
        transport.updateCount shouldBe 0
        transport.contents.getValue(remoteFile.remoteId) shouldBe encode(remote)
        transport.files.count {
            it.protocolVersion == 2 &&
                it.logicalKind == SyncDocumentKind.LIBRARY &&
                it.ownerDeviceId == "merge-device"
        } shouldBe 1
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
    fun `protocol v2 concurrent bootstrap creates one valid shard per device`() = runTest {
        val shared = FakeTransport(
            listSnapshots = mutableListOf(
                emptyList(),
                emptyList(),
            ),
        )
        val storesA = Stores()
        val storesB = Stores()
        val adapterA = FakeAdapter(
            SyncDocumentKind.LIBRARY,
            document(record("a", "name" to "A")),
        )
        val adapterB = FakeAdapter(
            SyncDocumentKind.LIBRARY,
            document(record("b", "name" to "B")),
        )

        engine(shared, storesA, adapterA, deviceId = "device:A").runOnce()
        engine(shared, storesB, adapterB, deviceId = "device:B").runOnce()

        shared.files
            .filter { it.protocolVersion == 2 && it.logicalKind == SyncDocumentKind.LIBRARY }
            .mapNotNull { it.ownerDeviceId }
            .sorted() shouldContainExactly listOf("device:A", "device:B")
        shared.createdKinds.contains(SyncDocumentKind.MANIFEST).shouldBeFalse()

        val convergence = engine(
            shared,
            storesA,
            adapterA,
            deviceId = "device:A",
        ).runOnce()
        (convergence.documentResults.single() as SyncDocumentResult.Synchronized)
            .localApplied.shouldBeTrue()
        adapterA.applied.last().records.keys.sorted() shouldContainExactly listOf("a", "b")
    }

    @Test
    fun `protocol v2 ambiguous create adopts reserved id without duplicate shard`() = runTest {
        val transport = FakeTransport(ambiguousCreateOnce = true)
        val stores = Stores()
        val adapter = FakeAdapter(
            SyncDocumentKind.LIBRARY,
            document(record("a", "name" to "A")),
        )

        val report = engine(
            transport,
            stores,
            adapter,
            deviceId = "device:A",
        ).runOnce()

        report.hasFailures.shouldBeFalse()
        transport.createCount shouldBe 1
        transport.getFileCount shouldBe 1
        transport.files.count {
            it.protocolVersion == 2 &&
                it.logicalKind == SyncDocumentKind.LIBRARY &&
                it.ownerDeviceId == "device:A"
        } shouldBe 1
        val replica = stores.replica.get(
            SyncDocumentKind.LIBRARY,
            "device:A",
        )
        replica?.reservedRemoteId shouldBe transport.files.single().remoteId
    }

    @Test
    fun `protocol v2 never updates a foreign owned shard`() = runTest {
        val shared = FakeTransport()
        val storesA = Stores()
        val storesB = Stores()
        val adapterA = FakeAdapter(
            SyncDocumentKind.LIBRARY,
            document(record("a", "name" to "A")),
        )
        val adapterB = FakeAdapter(
            SyncDocumentKind.LIBRARY,
            document(record("b", "name" to "B")),
        )

        engine(shared, storesA, adapterA, deviceId = "device:A").runOnce()
        engine(shared, storesB, adapterB, deviceId = "device:B").runOnce()

        storesA.outbox.markDirty(SyncDocumentKind.LIBRARY, 200)
        adapterA.applyDocument(
            document(
                record("a", "name" to "A2", device = "device:A", sequence = 2),
                record("b", "name" to "B"),
            ),
        )
        val foreignFile = shared.files.single {
            it.protocolVersion == 2 &&
                it.logicalKind == SyncDocumentKind.LIBRARY &&
                it.ownerDeviceId == "device:B"
        }
        val foreignContentBefore = shared.contents.getValue(foreignFile.remoteId)

        engine(shared, storesA, adapterA, deviceId = "device:A").runOnce()

        shared.contents.getValue(foreignFile.remoteId) shouldBe foreignContentBefore
        shared.replicaUpdateOwners.all { (fileOwner, requestedOwner) ->
            fileOwner == requestedOwner
        }.shouldBeTrue()
        shared.replicaUpdateOwners.none { (fileOwner, requestedOwner) ->
            fileOwner == "device:B" && requestedOwner == "device:A"
        }.shouldBeTrue()
    }

    @Test
    fun `protocol v2 rejects duplicate shards for the same owner`() = runTest {
        val first = replicaRemoteFile("one", "device:A")
        val second = replicaRemoteFile("two", "device:A")
        val transport = FakeTransport(
            files = mutableListOf(first, second),
            contents = mutableMapOf(
                first.remoteId to
                    """{"protocolVersion":2,"kind":"LIBRARY","ownerDeviceId":"device:A","genesis":null,"batches":[]}""",
                second.remoteId to
                    """{"protocolVersion":2,"kind":"LIBRARY","ownerDeviceId":"device:A","genesis":null,"batches":[]}""",
            ),
        )

        val report = engine(
            transport,
            Stores(),
            FakeAdapter(SyncDocumentKind.LIBRARY, document()),
            deviceId = "device:B",
        ).runOnce()

        (report.documentResults.single() as SyncDocumentResult.Failed)
            .failure.reason shouldBe SyncFailureReason.MALFORMED_REMOTE_DOCUMENT
        transport.replicaUpdateOwners shouldBe emptyList()
    }


    @Test
    fun `second v1 device migration detects stale same field conflict after v2 exists`() = runTest {
        val legacyBase = document(record("a", "name" to "Base"))
        val legacyCurrent = document(
            record(
                "a",
                "name" to "Remote",
                device = "legacy-remote",
                sequence = 2,
            ),
        )
        val legacyFile = remoteFile()
        val transport = FakeTransport(
            files = mutableListOf(legacyFile),
            contents = mutableMapOf(legacyFile.remoteId to encode(legacyCurrent)),
        )

        val firstDevice = Stores()
        val firstAdapter = FakeAdapter(SyncDocumentKind.LIBRARY, legacyCurrent)
        val firstReport = engine(
            transport,
            firstDevice,
            firstAdapter,
            deviceId = "device:A",
        ).runOnce()
        firstReport.hasFailures.shouldBeFalse()
        transport.files.count {
            it.protocolVersion == 2 &&
                it.logicalKind == SyncDocumentKind.LIBRARY &&
                it.ownerDeviceId == "device:A"
        } shouldBe 1

        val secondDevice = Stores(
            outboxEntries = mutableMapOf(
                SyncDocumentKind.LIBRARY to SyncOutboxEntry(
                    documentKind = SyncDocumentKind.LIBRARY,
                    enqueuedAtEpochMillis = 90,
                ),
            ),
        )
        secondDevice.state.put(
            SyncStoredState(
                documentKind = SyncDocumentKind.LIBRARY,
                acceptedBase = legacyBase,
                remoteRevision = legacyFile.revision,
                lastSuccessfulSyncAtEpochMillis = 50,
            ),
        )
        val secondAdapter = FakeAdapter(
            SyncDocumentKind.LIBRARY,
            document(
                record(
                    "a",
                    "name" to "Local",
                    device = "device:B",
                    sequence = 2,
                ),
            ),
        )

        val report = engine(
            transport,
            secondDevice,
            secondAdapter,
            deviceId = "device:B",
        ).runOnce()

        val result = report.documentResults.single() as SyncDocumentResult.Conflict
        result.conflictCount shouldBe 1
        transport.files.none {
            it.protocolVersion == 2 &&
                it.logicalKind == SyncDocumentKind.LIBRARY &&
                it.ownerDeviceId == "device:B"
        }.shouldBeTrue()
        secondDevice.state.get(SyncDocumentKind.LIBRARY)?.acceptedBase shouldBe legacyBase
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
        stores.outbox.get(SyncDocumentKind.LIBRARY) shouldBe
            SyncOutboxEntry(
                documentKind = SyncDocumentKind.LIBRARY,
                enqueuedAtEpochMillis = 100,
                attemptCount = 1,
                nextAttemptAtEpochMillis = 60_100,
            )
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
    fun `case 9 - dependent documents apply after title materializing documents`() = runTest {
        val chapterOverrides = FakeAdapter(
            SyncDocumentKind.CHAPTER_OVERRIDES,
            document(
                record("override", "enabled" to "true"),
                kind = SyncDocumentKind.CHAPTER_OVERRIDES,
            ),
        )
        val sourceMappings = FakeAdapter(
            SyncDocumentKind.SOURCE_MAPPINGS,
            document(
                record("mapping", "source" to "42"),
                kind = SyncDocumentKind.SOURCE_MAPPINGS,
            ),
        )
        val library = FakeAdapter(
            SyncDocumentKind.LIBRARY,
            document(record("title", "name" to "Tsuzuki")),
        )

        val report = engine(
            FakeTransport(),
            Stores(),
            chapterOverrides,
            sourceMappings,
            library,
        ).runOnce()

        report.documentResults.map { it.documentKind } shouldContainExactly listOf(
            SyncDocumentKind.LIBRARY,
            SyncDocumentKind.SOURCE_MAPPINGS,
            SyncDocumentKind.CHAPTER_OVERRIDES,
        )
    }

    @Test
    fun `case 10 - fallback progress adapter is rejected until milestone 12`() {
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
    fun `case 11 - local deletion is materialized as tombstone before merge`() = runTest {
        val base = document(record("a", "name" to "Base"))
        val local = document()
        val remote = base
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

        val result = report.documentResults.single() as SyncDocumentResult.Synchronized
        result.remoteWritten.shouldBeTrue()
        transport.contents.getValue(remoteFile.remoteId) shouldBe encode(remote)
        stores.state.get(SyncDocumentKind.LIBRARY)
            ?.acceptedBase
            ?.records
            ?.getValue("a")
            ?.isTombstone
            .shouldBeTrue()
    }

    @Test
    fun `case 12 - local shard remote change keeps accepted base unchanged`() = runTest {
        val base = document(record("a", "name" to "Base"))
        val adapter = FakeAdapter(SyncDocumentKind.LIBRARY, base)
        val transport = FakeTransport(
            updateFailure = SyncFailure(SyncFailureReason.REMOTE_CHANGED),
        )
        val stores = Stores()

        val first = engine(transport, stores, adapter).runOnce()
        (first.documentResults.single() as SyncDocumentResult.Synchronized)
            .remoteWritten.shouldBeTrue()
        val acceptedBefore = checkNotNull(
            stores.state.get(SyncDocumentKind.LIBRARY)?.acceptedBase,
        )

        stores.outbox.markDirty(
            documentKind = SyncDocumentKind.LIBRARY,
            enqueuedAtEpochMillis = 90,
        )
        adapter.applyDocument(
            document(
                record(
                    "a",
                    "name" to "Local",
                    device = "merge-device",
                    sequence = 2,
                ),
            ),
        )

        val report = engine(transport, stores, adapter).runOnce()

        (report.documentResults.single() as SyncDocumentResult.Failed)
            .failure.reason shouldBe SyncFailureReason.REMOTE_CHANGED
        stores.state.get(SyncDocumentKind.LIBRARY)?.acceptedBase shouldBe acceptedBefore
        stores.outbox.get(SyncDocumentKind.LIBRARY) shouldBe
            SyncOutboxEntry(
                documentKind = SyncDocumentKind.LIBRARY,
                enqueuedAtEpochMillis = 90,
                attemptCount = 1,
                nextAttemptAtEpochMillis = 60_100,
            )
    }

    private fun engine(
        transport: DriveSyncTransport,
        stores: Stores,
        vararg adapters: SyncDocumentAdapter,
        deviceId: String = "merge-device",
    ) = SyncCycleOrchestrator(
        transport = transport,
        outboxRepository = stores.outbox,
        stateRepository = stores.state,
        replicaRepository = stores.replica,
        conflictRepository = stores.conflicts,
        adapters = adapters.toList(),
        codec = codec,
        journalCodec = journalCodec,
        differ = differ,
        materializer = materializer,
        bootstrap = bootstrap,
        merger = ThreeWaySyncMerger(),
        revisionSource = object : SyncRevisionSource {
            override val deviceId = deviceId
            private var sequence = 10L

            override fun nextRevision() = SyncRevision(
                deviceId = deviceId,
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

    private fun replicaRemoteFile(
        id: String,
        ownerDeviceId: String,
        version: String = "1",
    ) = SyncRemoteFile(
        remoteId = id,
        name = "tsuzuki-v2-library-$ownerDeviceId.json",
        mimeType = "application/json",
        revision = SyncRemoteRevision(
            remoteId = id,
            revisionToken = version,
        ),
        protocolVersion = 2,
        logicalKind = SyncDocumentKind.LIBRARY,
        ownerDeviceId = ownerDeviceId,
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
        private val listSnapshots: MutableList<List<SyncRemoteFile>> = mutableListOf(),
        ambiguousCreateOnce: Boolean = false,
    ) : DriveSyncTransport {
        val createdKinds = mutableListOf<SyncDocumentKind>()
        var createCount = 0
        var updateCount = 0
        var downloadCount = 0
        var getFileCount = 0
        private var ambiguousCreateRemaining = ambiguousCreateOnce
        val replicaUpdateOwners = mutableListOf<Pair<String?, String>>()

        override suspend fun listFiles(): SyncTransportResult<List<SyncRemoteFile>> {
            val snapshot = if (listSnapshots.isNotEmpty()) {
                listSnapshots.removeAt(0)
            } else {
                files.toList()
            }
            return SyncTransportResult.Success(snapshot)
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

        override suspend fun generateFileId(): SyncTransportResult<String> =
            SyncTransportResult.Success("reserved-${files.size + 1}")

        override suspend fun createReplica(
            remoteId: String,
            documentKind: SyncDocumentKind,
            ownerDeviceId: String,
            content: String,
        ): SyncTransportResult<SyncRemoteFile> {
            createCount += 1
            createFailure?.let { return SyncTransportResult.Failure(it) }
            val file = SyncRemoteFile(
                remoteId = remoteId,
                name = "tsuzuki-v2-${documentKind.name.lowercase()}-$ownerDeviceId.json",
                mimeType = "application/json",
                revision = SyncRemoteRevision(
                    remoteId = remoteId,
                    revisionToken = "1",
                ),
                protocolVersion = 2,
                logicalKind = documentKind,
                ownerDeviceId = ownerDeviceId,
            )
            files += file
            contents[file.remoteId] = content
            if (ambiguousCreateRemaining) {
                ambiguousCreateRemaining = false
                return SyncTransportResult.Failure(
                    SyncFailure(SyncFailureReason.REMOTE_CHANGED),
                )
            }
            return SyncTransportResult.Success(file)
        }

        override suspend fun updateOwnedReplica(
            file: SyncRemoteFile,
            ownerDeviceId: String,
            content: String,
        ): SyncTransportResult<SyncRemoteFile> {
            replicaUpdateOwners += file.ownerDeviceId to ownerDeviceId
            if (file.ownerDeviceId != ownerDeviceId) {
                return SyncTransportResult.Failure(
                    SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT),
                )
            }
            return update(file, content)
        }

        override suspend fun getFile(remoteId: String): SyncTransportResult<SyncRemoteFile> {
            getFileCount += 1
            val file = files.singleOrNull { it.remoteId == remoteId }
                ?: return SyncTransportResult.Failure(
                    SyncFailure(SyncFailureReason.REMOTE_NOT_FOUND),
                )
            return SyncTransportResult.Success(file)
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

        override suspend fun generateFileId(): SyncTransportResult<String> =
            error("not used")

        override suspend fun createReplica(
            remoteId: String,
            documentKind: SyncDocumentKind,
            ownerDeviceId: String,
            content: String,
        ): SyncTransportResult<SyncRemoteFile> = error("not used")

        override suspend fun updateOwnedReplica(
            file: SyncRemoteFile,
            ownerDeviceId: String,
            content: String,
        ): SyncTransportResult<SyncRemoteFile> = error("not used")

        override suspend fun getFile(remoteId: String): SyncTransportResult<SyncRemoteFile> =
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
        val replica = InMemoryReplica()
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

    private class InMemoryReplica : SyncReplicaRepository {
        private val states = mutableMapOf<Pair<SyncDocumentKind, String>, SyncReplicaState>()

        override suspend fun get(
            documentKind: SyncDocumentKind,
            ownerDeviceId: String,
        ): SyncReplicaState? = states[documentKind to ownerDeviceId]

        override suspend fun put(state: SyncReplicaState) {
            states[state.documentKind to state.ownerDeviceId] = state
        }

        override suspend fun clear(
            documentKind: SyncDocumentKind,
            ownerDeviceId: String,
        ) {
            states.remove(documentKind to ownerDeviceId)
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
