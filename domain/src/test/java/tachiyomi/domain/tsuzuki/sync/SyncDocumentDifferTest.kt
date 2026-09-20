package tachiyomi.domain.tsuzuki.sync

import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncMutation
import tachiyomi.domain.tsuzuki.sync.model.SyncRecordEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision
import tachiyomi.domain.tsuzuki.sync.service.SyncDocumentDiffer

class SyncDocumentDifferTest {

    private val differ = SyncDocumentDiffer()

    @Test
    fun `diff ignores generated revisions and emits deterministic leaf mutations`() {
        val base = document(
            revision = SyncRevision("device:A", 1),
            generatedAt = 1,
            record = record(
                revision = SyncRevision("device:A", 1),
                updatedAt = 10,
                fields = buildJsonObject {
                    put("name", "Base")
                    put(
                        "nested",
                        buildJsonObject {
                            put("a", 1)
                            put("b", 2)
                        },
                    )
                    put("tags", JsonArray(listOf(JsonPrimitive("x"))))
                },
            ),
        )
        val sameSemantic = document(
            revision = SyncRevision("device:A", 99),
            generatedAt = 99,
            record = record(
                revision = SyncRevision("device:A", 77),
                updatedAt = 10,
                fields = base.records.getValue("a").fields,
            ),
        )
        differ.diff(base, sameSemantic) shouldContainExactly emptyList()

        val changed = document(
            revision = SyncRevision("device:A", 100),
            generatedAt = 100,
            record = record(
                revision = SyncRevision("device:A", 88),
                updatedAt = 20,
                fields = buildJsonObject {
                    put("name", "Changed")
                    put(
                        "nested",
                        buildJsonObject {
                            put("a", 1)
                            put("c", 3)
                        },
                    )
                    put("tags", JsonArray(listOf(JsonPrimitive("x"), JsonPrimitive("y"))))
                },
            ),
        )

        differ.diff(base, changed) shouldContainExactly listOf(
            SyncMutation.SetField("a", listOf("name"), JsonPrimitive("Changed"), 20),
            SyncMutation.RemoveField("a", listOf("nested", "b"), 20),
            SyncMutation.SetField("a", listOf("nested", "c"), JsonPrimitive(3), 20),
            SyncMutation.SetField(
                "a",
                listOf("tags"),
                JsonArray(listOf(JsonPrimitive("x"), JsonPrimitive("y"))),
                20,
            ),
        )
    }

    @Test
    fun `diff creates and deletes records with record metadata`() {
        val created = document(
            record = record(
                updatedAt = 30,
                fields = buildJsonObject {
                    put("name", "New")
                    put("nested", buildJsonObject { put("a", true) })
                },
            ),
        )
        differ.diff(null, created) shouldContainExactly listOf(
            SyncMutation.SetField("a", listOf("name"), JsonPrimitive("New"), 30),
            SyncMutation.SetField("a", listOf("nested", "a"), JsonPrimitive(true), 30),
        )

        val deleted = created.copy(
            records = mapOf(
                "a" to created.records.getValue("a").copy(
                    updatedAtEpochMillis = 40,
                    deletedAtEpochMillis = 40,
                ),
            ),
        )
        differ.diff(created, deleted) shouldContainExactly listOf(
            SyncMutation.DeleteRecord(
                recordId = "a",
                recordUpdatedAtEpochMillis = 40,
                deletedAtEpochMillis = 40,
            ),
        )
    }

    private fun document(
        revision: SyncRevision = SyncRevision("device:A", 1),
        generatedAt: Long = 1,
        record: SyncRecordEnvelope = record(),
    ) = SyncDocumentEnvelope(
        schemaVersion = 1,
        kind = SyncDocumentKind.LIBRARY,
        revision = revision,
        generatedAtEpochMillis = generatedAt,
        records = mapOf(record.id to record),
    )

    private fun record(
        revision: SyncRevision = SyncRevision("device:A", 1),
        updatedAt: Long = 10,
        fields: kotlinx.serialization.json.JsonObject = buildJsonObject { put("name", "Base") },
    ) = SyncRecordEnvelope(
        id = "a",
        revision = revision,
        updatedAtEpochMillis = updatedAt,
        fields = fields,
    )
}
