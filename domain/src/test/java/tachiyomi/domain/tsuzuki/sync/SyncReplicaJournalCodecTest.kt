package tachiyomi.domain.tsuzuki.sync

import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.sync.model.SyncCodecResult
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncFrontier
import tachiyomi.domain.tsuzuki.sync.model.SyncMutation
import tachiyomi.domain.tsuzuki.sync.model.SyncMutationBatch
import tachiyomi.domain.tsuzuki.sync.model.SyncRecordEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncReplicaGenesis
import tachiyomi.domain.tsuzuki.sync.model.SyncReplicaJournal
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision
import tachiyomi.domain.tsuzuki.sync.service.SyncReplicaJournalCodec

class SyncReplicaJournalCodecTest {

    private val codec = SyncReplicaJournalCodec(
        Json {
            encodeDefaults = true
        },
    )

    @Test
    fun `frontier validates and advances monotonically`() {
        shouldThrow<IllegalArgumentException> {
            SyncFrontier(mapOf("device:A" to -1))
        }

        val empty = SyncFrontier()
        empty.observes(SyncRevision("device:A", 1)) shouldBe false

        val one = empty.advance(SyncRevision("device:A", 1))
        one.observes(SyncRevision("device:A", 1)) shouldBe true
        one.advance(SyncRevision("device:A", 0)) shouldBe one

        one.mergedWith(SyncFrontier(mapOf("device:A" to 2, "device:B" to 3))) shouldBe
            SyncFrontier(mapOf("device:A" to 2, "device:B" to 3))
    }

    @Test
    fun `journal rejects invalid owner sequence and genesis kind`() {
        shouldThrow<IllegalArgumentException> {
            journal(owner = "")
        }
        shouldThrow<IllegalArgumentException> {
            journal(
                batches = listOf(
                    batch(
                        revision = SyncRevision("device:B", 1),
                    ),
                ),
            )
        }
        shouldThrow<IllegalArgumentException> {
            journal(
                batches = listOf(
                    batch(revision = SyncRevision("device:A", 2)),
                    batch(revision = SyncRevision("device:A", 2)),
                ),
            )
        }
        shouldThrow<IllegalArgumentException> {
            journal(
                batches = listOf(
                    batch(
                        revision = SyncRevision("device:A", 2),
                        observed = SyncFrontier(mapOf("device:A" to 2)),
                    ),
                ),
            )
        }
        shouldThrow<IllegalArgumentException> {
            journal(
                genesis = SyncReplicaGenesis(
                    sourceRemoteId = "legacy",
                    sourceRevisionToken = "7",
                    document = document(kind = SyncDocumentKind.COLLECTIONS),
                ),
            )
        }
    }

    @Test
    fun `codec round trip is canonical and deterministic`() {
        val value = journal(
            batches = listOf(
                batch(
                    mutations = listOf(
                        SyncMutation.SetField(
                            recordId = "a",
                            propertyPath = listOf("name"),
                            value = JsonPrimitive("Tsuzuki"),
                            recordUpdatedAtEpochMillis = 10,
                        ),
                    ),
                ),
            ),
        )

        val first = codec.encode(value) as SyncCodecResult.Success
        val decoded = codec.decode(first.value) as SyncCodecResult.Success
        val second = codec.encode(decoded.value) as SyncCodecResult.Success

        decoded.value shouldBe value
        second.value shouldBe first.value
    }

    private fun journal(
        owner: String = "device:A",
        genesis: SyncReplicaGenesis? = null,
        batches: List<SyncMutationBatch> = emptyList(),
    ) = SyncReplicaJournal(
        kind = SyncDocumentKind.LIBRARY,
        ownerDeviceId = owner,
        genesis = genesis,
        batches = batches,
    )

    private fun batch(
        revision: SyncRevision = SyncRevision("device:A", 1),
        observed: SyncFrontier = SyncFrontier(),
        mutations: List<SyncMutation> = emptyList(),
    ) = SyncMutationBatch(
        revision = revision,
        observed = observed,
        generatedAtEpochMillis = 10,
        mutations = mutations,
    )

    private fun document(
        kind: SyncDocumentKind = SyncDocumentKind.LIBRARY,
    ) = SyncDocumentEnvelope(
        schemaVersion = 1,
        kind = kind,
        revision = SyncRevision("legacy", 1),
        generatedAtEpochMillis = 1,
        records = mapOf(
            "a" to SyncRecordEnvelope(
                id = "a",
                revision = SyncRevision("legacy", 1),
                updatedAtEpochMillis = 1,
                fields = kotlinx.serialization.json.buildJsonObject {
                    put("name", "Base")
                },
            ),
        ),
    )
}
