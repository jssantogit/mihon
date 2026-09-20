package tachiyomi.domain.tsuzuki.sync

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictKind
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncFrontier
import tachiyomi.domain.tsuzuki.sync.model.SyncMutation
import tachiyomi.domain.tsuzuki.sync.model.SyncMutationBatch
import tachiyomi.domain.tsuzuki.sync.model.SyncRecordEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncReplicaGenesis
import tachiyomi.domain.tsuzuki.sync.model.SyncReplicaJournal
import tachiyomi.domain.tsuzuki.sync.model.SyncReplicaMaterializationResult
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision
import tachiyomi.domain.tsuzuki.sync.service.SyncReplicaMaterializer

class SyncReplicaMaterializerTest {

    private val materializer = SyncReplicaMaterializer()

    @Test
    fun `concurrent independent fields merge and causally later value supersedes stale value`() {
        val a = journal(
            "device:A",
            batch(
                "device:A",
                1,
                mutations = listOf(set("r", "name", "A", 10)),
            ),
            batch(
                "device:A",
                2,
                observed = frontier("device:A" to 1, "device:B" to 1),
                mutations = listOf(set("r", "name", "A2", 30)),
            ),
        )
        val b = journal(
            "device:B",
            batch(
                "device:B",
                1,
                mutations = listOf(set("r", "status", "reading", 20)),
            ),
        )

        val result = success(materializer.materialize(kind, 1, listOf(a, b), 50))
        result.conflicts shouldContainExactly emptyList()
        result.document.records.getValue("r").fields["name"] shouldBe JsonPrimitive("A2")
        result.document.records.getValue("r").fields["status"] shouldBe JsonPrimitive("reading")
        result.frontier shouldBe frontier("device:A" to 2, "device:B" to 1)
    }

    @Test
    fun `same field concurrent edits produce conflict without arbitrary winner`() {
        val a = journal("device:A", batch("device:A", 1, mutations = listOf(set("r", "name", "A", 10))))
        val b = journal("device:B", batch("device:B", 1, mutations = listOf(set("r", "name", "B", 20))))

        val result = success(materializer.materialize(kind, 1, listOf(a, b), 50))

        result.conflicts.size shouldBe 1
        result.conflicts.single().kind shouldBe SyncConflictKind.FIELD_DIVERGENCE
        result.conflicts.single().recordId shouldBe "r"
        result.conflicts.single().propertyPath shouldBe listOf("name")
    }

    @Test
    fun `three concurrent values expose every contender deterministically independent of listing order`() {
        val journals = listOf(
            journal("device:C", batch("device:C", 1, mutations = listOf(set("r", "name", "C", 30)))),
            journal("device:A", batch("device:A", 1, mutations = listOf(set("r", "name", "A", 10)))),
            journal("device:B", batch("device:B", 1, mutations = listOf(set("r", "name", "B", 20)))),
        )
        val forward = success(materializer.materialize(kind, 1, journals, 50))
        val reverse = success(materializer.materialize(kind, 1, journals.reversed(), 50))

        forward.conflicts shouldBe reverse.conflicts
        forward.conflicts.size shouldBe 2
    }

    @Test
    fun `delete concurrent with edit conflicts while causal recreation becomes active`() {
        val aDelete = batch(
            "device:A",
            1,
            mutations = listOf(
                SyncMutation.DeleteRecord("r", 20, 20),
            ),
        )
        val bEdit = batch("device:B", 1, mutations = listOf(set("r", "name", "B", 20)))
        val concurrent = success(
            materializer.materialize(
                kind,
                1,
                listOf(journal("device:A", aDelete), journal("device:B", bEdit)),
                50,
            ),
        )
        concurrent.conflicts.single().kind shouldBe SyncConflictKind.DELETE_EDIT

        val recreate = batch(
            "device:B",
            2,
            observed = frontier("device:A" to 1, "device:B" to 1),
            mutations = listOf(set("r", "name", "Recreated", 30)),
        )
        val causal = success(
            materializer.materialize(
                kind,
                1,
                listOf(journal("device:A", aDelete), journal("device:B", bEdit, recreate)),
                50,
            ),
        )
        causal.conflicts shouldContainExactly emptyList()
        causal.document.records.getValue("r").isTombstone shouldBe false
        causal.document.records.getValue("r").fields["name"] shouldBe JsonPrimitive("Recreated")
    }

    @Test
    fun `visible frontier reconstructs only causally observed history`() {
        val a = journal(
            "device:A",
            batch("device:A", 1, mutations = listOf(set("r", "name", "A", 10))),
        )
        val b = journal(
            "device:B",
            batch("device:B", 1, mutations = listOf(set("r", "status", "remote", 20))),
        )

        val visible = success(
            materializer.materialize(
                kind = kind,
                schemaVersion = 1,
                journals = listOf(a, b),
                materializedAtEpochMillis = 50,
                visibleFrontier = frontier("device:A" to 1),
            ),
        )

        visible.document.records.getValue("r").fields["name"] shouldBe JsonPrimitive("A")
        visible.document.records.getValue("r").fields.containsKey("status") shouldBe false
        visible.frontier shouldBe frontier("device:A" to 1)
    }

    @Test
    fun `frontier referencing an unpublished sequence fails closed`() {
        val a = journal(
            "device:A",
            batch("device:A", 1, mutations = listOf(set("r", "name", "A1", 10))),
            batch(
                "device:A",
                3,
                observed = frontier("device:A" to 2),
                mutations = listOf(set("r", "name", "A3", 30)),
            ),
        )

        val result = materializer.materialize(kind, 1, listOf(a), 50)

        (result as SyncReplicaMaterializationResult.Failure).failure.reason shouldBe
            tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason.MALFORMED_REMOTE_DOCUMENT
    }

    @Test
    fun `visible frontier referencing an unpublished sequence fails closed`() {
        val a = journal(
            "device:A",
            batch("device:A", 1, mutations = listOf(set("r", "name", "A1", 10))),
            batch("device:A", 3, mutations = listOf(set("r", "name", "A3", 30))),
        )

        val result = materializer.materialize(
            kind = kind,
            schemaVersion = 1,
            journals = listOf(a),
            materializedAtEpochMillis = 50,
            visibleFrontier = frontier("device:A" to 2),
        )

        (result as SyncReplicaMaterializationResult.Failure).failure.reason shouldBe
            tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason.MALFORMED_REMOTE_DOCUMENT
    }

    @Test
    fun `legacy genesis must be copied identically by every participating replica`() {
        val genesis = legacyGenesis()
        val withGenesis = journalWithGenesis(
            "device:A",
            genesis,
            batch("device:A", 1, mutations = listOf(set("r", "status", "reading", 20))),
        )
        val withoutGenesis = journal(
            "device:B",
            batch("device:B", 1, mutations = listOf(set("r", "favorite", "true", 20))),
        )

        val result = materializer.materialize(kind, 1, listOf(withGenesis, withoutGenesis), 50)

        (result as SyncReplicaMaterializationResult.Failure).failure.reason shouldBe
            tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason.MALFORMED_REMOTE_DOCUMENT
    }

    @Test
    fun `identical legacy genesis supports independent replica deltas without false conflict`() {
        val genesis = legacyGenesis()
        val a = journalWithGenesis(
            "device:A",
            genesis,
            batch("device:A", 1, mutations = listOf(set("r", "status", "reading", 20))),
        )
        val b = journalWithGenesis(
            "device:B",
            genesis,
            batch("device:B", 1, mutations = listOf(set("r", "favorite", "true", 30))),
        )

        val result = success(materializer.materialize(kind, 1, listOf(a, b), 50))

        result.conflicts shouldContainExactly emptyList()
        result.document.records.getValue("r").fields["name"] shouldBe JsonPrimitive("Base")
        result.document.records.getValue("r").fields["status"] shouldBe JsonPrimitive("reading")
        result.document.records.getValue("r").fields["favorite"] shouldBe JsonPrimitive("true")
    }

    @Test
    fun `partially connected three replica history converges independent of listing order`() {
        val a = journal(
            "device:A",
            batch("device:A", 1, mutations = listOf(set("r", "name", "A", 10))),
        )
        val b = journal(
            "device:B",
            batch(
                "device:B",
                1,
                observed = frontier("device:A" to 1),
                mutations = listOf(set("r", "status", "reading", 20)),
            ),
        )
        val c = journal(
            "device:C",
            batch(
                "device:C",
                1,
                observed = frontier("device:A" to 1),
                mutations = listOf(set("r", "favorite", "true", 30)),
            ),
        )
        val orders = listOf(
            listOf(a, b, c),
            listOf(a, c, b),
            listOf(b, a, c),
            listOf(b, c, a),
            listOf(c, a, b),
            listOf(c, b, a),
        )

        val results = orders.map {
            success(materializer.materialize(kind, 1, it, 50))
        }

        results.drop(1).forEach { it shouldBe results.first() }
    }


    @Test
    fun `fresh replicas with different records both survive`() {
        val a = journal(
            "device:A",
            batch("device:A", 1, mutations = listOf(set("a", "name", "A", 10))),
        )
        val b = journal(
            "device:B",
            batch("device:B", 1, mutations = listOf(set("b", "name", "B", 20))),
        )

        val result = success(materializer.materialize(kind, 1, listOf(a, b), 50))

        result.conflicts shouldContainExactly emptyList()
        result.document.records.keys.toList() shouldContainExactly listOf("a", "b")
    }

    @Test
    fun `equivalent concurrent values collapse without conflict`() {
        val a = journal(
            "device:A",
            batch("device:A", 1, mutations = listOf(set("r", "name", "Same", 10))),
        )
        val b = journal(
            "device:B",
            batch("device:B", 1, mutations = listOf(set("r", "name", "Same", 20))),
        )

        val result = success(materializer.materialize(kind, 1, listOf(a, b), 50))

        result.conflicts shouldContainExactly emptyList()
        result.document.records.getValue("r").fields["name"] shouldBe JsonPrimitive("Same")
    }

    @Test
    fun `causally later delete wins over earlier active value`() {
        val active = batch(
            "device:A",
            1,
            mutations = listOf(set("r", "name", "Alive", 10)),
        )
        val deletion = batch(
            "device:B",
            1,
            observed = frontier("device:A" to 1),
            mutations = listOf(SyncMutation.DeleteRecord("r", 20, 20)),
        )

        val result = success(
            materializer.materialize(
                kind,
                1,
                listOf(journal("device:A", active), journal("device:B", deletion)),
                50,
            ),
        )

        result.conflicts shouldContainExactly emptyList()
        result.document.records.getValue("r").isTombstone shouldBe true
    }

    @Test
    fun `different non null legacy genesis values fail closed`() {
        val first = legacyGenesis()
        val second = first.copy(sourceRevisionToken = "8")

        val result = materializer.materialize(
            kind,
            1,
            listOf(
                journalWithGenesis("device:A", first),
                journalWithGenesis("device:B", second),
            ),
            50,
        )

        (result as SyncReplicaMaterializationResult.Failure).failure.reason shouldBe
            tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason.MALFORMED_REMOTE_DOCUMENT
    }

    private fun success(result: SyncReplicaMaterializationResult) =
        result as SyncReplicaMaterializationResult.Success

    private fun journal(
        owner: String,
        vararg batches: SyncMutationBatch,
    ) = SyncReplicaJournal(
        kind = kind,
        ownerDeviceId = owner,
        batches = batches.toList(),
    )

    private fun journalWithGenesis(
        owner: String,
        genesis: SyncReplicaGenesis,
        vararg batches: SyncMutationBatch,
    ) = SyncReplicaJournal(
        kind = kind,
        ownerDeviceId = owner,
        genesis = genesis,
        batches = batches.toList(),
    )

    private fun batch(
        device: String,
        sequence: Long,
        observed: SyncFrontier = SyncFrontier(),
        mutations: List<SyncMutation>,
    ) = SyncMutationBatch(
        revision = SyncRevision(device, sequence),
        observed = observed,
        generatedAtEpochMillis = sequence * 10,
        mutations = mutations,
    )

    private fun set(
        recordId: String,
        property: String,
        value: String,
        updatedAt: Long,
    ) = SyncMutation.SetField(
        recordId = recordId,
        propertyPath = listOf(property),
        value = JsonPrimitive(value),
        recordUpdatedAtEpochMillis = updatedAt,
    )

    private fun frontier(vararg values: Pair<String, Long>) =
        SyncFrontier(linkedMapOf(*values))

    private fun legacyGenesis() = SyncReplicaGenesis(
        sourceRemoteId = "legacy-library",
        sourceRevisionToken = "7",
        document = SyncDocumentEnvelope(
            schemaVersion = 1,
            kind = kind,
            revision = SyncRevision("legacy", 1),
            generatedAtEpochMillis = 5,
            records = mapOf(
                "r" to SyncRecordEnvelope(
                    id = "r",
                    revision = SyncRevision("legacy", 1),
                    updatedAtEpochMillis = 5,
                    fields = kotlinx.serialization.json.buildJsonObject {
                        put("name", JsonPrimitive("Base"))
                    },
                ),
            ),
        ),
    )

    private companion object {
        val kind = SyncDocumentKind.LIBRARY
    }
}
