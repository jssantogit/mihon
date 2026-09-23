package tachiyomi.domain.tsuzuki.sync

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictKind
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason
import tachiyomi.domain.tsuzuki.sync.model.SyncMergeResult
import tachiyomi.domain.tsuzuki.sync.model.SyncRecordEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision
import tachiyomi.domain.tsuzuki.sync.service.ThreeWaySyncMerger

class ThreeWaySyncMergerTest {

    private val merger = ThreeWaySyncMerger()
    private val mergeRevision = SyncRevision("merge-device", 99)

    @Test
    fun `case 1 - independent records merge automatically`() {
        val base = document(
            record("a", fields("name" to "A")),
            record("b", fields("status" to "reading")),
        )
        val local = document(
            record("a", fields("name" to "B"), device = "phone", sequence = 2),
            record("b", fields("status" to "reading")),
        )
        val remote = document(
            record("a", fields("name" to "A")),
            record("b", fields("status" to "completed"), device = "tablet", sequence = 2),
        )

        val outcome = success(merge(base, local, remote))

        outcome.conflicts shouldBe emptyList()
        outcome.records.getValue("a").fields["name"] shouldBe JsonPrimitive("B")
        outcome.records.getValue("b").fields["status"] shouldBe JsonPrimitive("completed")
        outcome.requiresLocalApply.shouldBeTrue()
        outcome.requiresRemoteWrite.shouldBeTrue()
    }

    @Test
    fun `case 2 - independent fields on one record merge recursively`() {
        val base = document(
            record("a", fields("name" to "A", "status" to "reading")),
        )
        val local = document(
            record("a", fields("name" to "B", "status" to "reading"), device = "phone", sequence = 2),
        )
        val remote = document(
            record("a", fields("name" to "A", "status" to "completed"), device = "tablet", sequence = 2),
        )

        val outcome = success(merge(base, local, remote))
        val merged = outcome.records.getValue("a")

        merged.fields["name"] shouldBe JsonPrimitive("B")
        merged.fields["status"] shouldBe JsonPrimitive("completed")
        merged.revision shouldBe mergeRevision
        outcome.conflicts shouldBe emptyList()
    }

    @Test
    fun `case 3 - same field divergence becomes explicit conflict and blocks upload`() {
        val base = document(record("a", fields("name" to "A")))
        val local = document(record("a", fields("name" to "Phone"), device = "phone", sequence = 2))
        val remote = document(record("a", fields("name" to "Tablet"), device = "tablet", sequence = 2))

        val outcome = success(merge(base, local, remote))

        outcome.conflicts.size shouldBe 1
        outcome.conflicts.single().kind shouldBe SyncConflictKind.FIELD_DIVERGENCE
        outcome.conflicts.single().propertyPath shouldContainExactly listOf("name")
        outcome.records.getValue("a").fields["name"] shouldBe JsonPrimitive("Phone")
        outcome.requiresRemoteWrite.shouldBeFalse()
    }

    @Test
    fun `case 4 - identical concurrent edit converges without conflict or extra write`() {
        val base = document(record("a", fields("name" to "A")))
        val local = document(record("a", fields("name" to "B"), device = "phone", sequence = 2))
        val remote = document(record("a", fields("name" to "B"), device = "tablet", sequence = 2))

        val outcome = success(merge(base, local, remote))

        outcome.conflicts shouldBe emptyList()
        outcome.requiresLocalApply.shouldBeFalse()
        outcome.requiresRemoteWrite.shouldBeFalse()
    }

    @Test
    fun `case 5 - tombstone beats unchanged record`() {
        val baseRecord = record("a", fields("name" to "A"))
        val base = document(baseRecord)
        val local = document(tombstone("a", device = "phone", sequence = 2))
        val remote = document(baseRecord)

        val outcome = success(merge(base, local, remote))

        outcome.records.getValue("a").isTombstone.shouldBeTrue()
        outcome.conflicts shouldBe emptyList()
        outcome.requiresRemoteWrite.shouldBeTrue()
    }

    @Test
    fun `case 6 - concurrent delete and edit becomes explicit conflict`() {
        val base = document(record("a", fields("name" to "A")))
        val local = document(tombstone("a", device = "phone", sequence = 2))
        val remote = document(record("a", fields("name" to "B"), device = "tablet", sequence = 2))

        val outcome = success(merge(base, local, remote))

        outcome.conflicts.single().kind shouldBe SyncConflictKind.DELETE_EDIT
        outcome.records.getValue("a").isTombstone.shouldBeTrue()
        outcome.requiresRemoteWrite.shouldBeFalse()
    }

    @Test
    fun `case 7 - nested object independent changes merge at property level`() {
        val base = document(
            record(
                "a",
                buildJsonObject {
                    put(
                        "query",
                        buildJsonObject {
                            put("sort", "popular")
                            put("limit", 20)
                        },
                    )
                },
            ),
        )
        val local = document(
            record(
                "a",
                buildJsonObject {
                    put(
                        "query",
                        buildJsonObject {
                            put("sort", "recent")
                            put("limit", 20)
                        },
                    )
                },
                device = "phone",
                sequence = 2,
            ),
        )
        val remote = document(
            record(
                "a",
                buildJsonObject {
                    put(
                        "query",
                        buildJsonObject {
                            put("sort", "popular")
                            put("limit", 30)
                        },
                    )
                },
                device = "tablet",
                sequence = 2,
            ),
        )

        val outcome = success(merge(base, local, remote))
        val query = outcome.records.getValue("a").fields.getValue("query") as JsonObject

        query["sort"] shouldBe JsonPrimitive("recent")
        query["limit"] shouldBe JsonPrimitive(30)
        outcome.conflicts shouldBe emptyList()
    }

    @Test
    fun `case 8 - incompatible schema is rejected before merge`() {
        val base = document(record("a", fields("name" to "A")), schemaVersion = 1)
        val local = document(record("a", fields("name" to "A")), schemaVersion = 1)
        val remote = document(record("a", fields("name" to "A")), schemaVersion = 2)

        val result = merge(base, local, remote)

        (result as SyncMergeResult.Rejected).failure.reason shouldBe
            SyncFailureReason.UNSUPPORTED_SCHEMA
    }

    @Test
    fun `case 9 - disappearing base record without tombstone fails closed`() {
        val base = document(record("a", fields("name" to "A")))
        val local = document(record("a", fields("name" to "A")))
        val remote = document()

        val result = merge(base, local, remote)

        (result as SyncMergeResult.Rejected).failure.reason shouldBe
            SyncFailureReason.MALFORMED_DOCUMENT
    }

    @Test
    fun `case 10 - independently created record fields can merge without a base`() {
        val base = document()
        val local = document(
            record("a", fields("name" to "A"), device = "phone", sequence = 2),
        )
        val remote = document(
            record("a", fields("status" to "reading"), device = "tablet", sequence = 2),
        )

        val outcome = success(merge(base, local, remote))
        val merged = outcome.records.getValue("a")

        merged.fields["name"] shouldBe JsonPrimitive("A")
        merged.fields["status"] shouldBe JsonPrimitive("reading")
        outcome.conflicts shouldBe emptyList()
    }

    private fun merge(
        base: SyncDocumentEnvelope?,
        local: SyncDocumentEnvelope,
        remote: SyncDocumentEnvelope,
    ): SyncMergeResult {
        return merger.merge(
            base = base,
            local = local,
            remote = remote,
            mergeRevision = mergeRevision,
            mergedAtEpochMillis = 1_000,
        )
    }

    private fun success(result: SyncMergeResult) =
        (result as SyncMergeResult.Success).outcome

    private fun document(
        vararg records: SyncRecordEnvelope,
        schemaVersion: Int = 1,
    ) = SyncDocumentEnvelope(
        schemaVersion = schemaVersion,
        kind = SyncDocumentKind.LIBRARY,
        revision = SyncRevision("document", 1),
        generatedAtEpochMillis = 100,
        records = records.associateBy { it.id },
    )

    private fun record(
        id: String,
        fields: JsonObject,
        device: String = "base",
        sequence: Long = 1,
    ) = SyncRecordEnvelope(
        id = id,
        revision = SyncRevision(device, sequence),
        updatedAtEpochMillis = sequence * 100,
        fields = fields,
    )

    private fun tombstone(
        id: String,
        device: String,
        sequence: Long,
    ) = SyncRecordEnvelope(
        id = id,
        revision = SyncRevision(device, sequence),
        updatedAtEpochMillis = sequence * 100,
        deletedAtEpochMillis = sequence * 100,
        fields = buildJsonObject {},
    )

    private fun fields(vararg values: Pair<String, String>) =
        buildJsonObject {
            values.forEach { (key, value) -> put(key, value) }
        }
}
