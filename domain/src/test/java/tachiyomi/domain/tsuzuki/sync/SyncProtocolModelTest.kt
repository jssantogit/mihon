package tachiyomi.domain.tsuzuki.sync

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeUnique
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncManifest
import tachiyomi.domain.tsuzuki.sync.model.SyncManifestEntry
import tachiyomi.domain.tsuzuki.sync.model.SyncRecordEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncRemoteRevision
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision

class SyncProtocolModelTest {

    private val revision = SyncRevision(
        deviceId = "device-a",
        sequence = 7,
    )

    @Test
    fun `case 1 - logical document file names are unique`() {
        SyncDocumentKind.entries.map { it.fileName }.shouldBeUnique()
    }

    @Test
    fun `case 2 - fallback progress exists as reserved protocol kind`() {
        SyncDocumentKind.FALLBACK_PROGRESS.fileName shouldBe "fallback-progress.json"
    }

    @Test
    fun `case 3 - record tombstone is explicit and retains stable identity`() {
        val record = SyncRecordEnvelope(
            id = "title-1",
            revision = revision,
            updatedAtEpochMillis = 100,
            deletedAtEpochMillis = 120,
            fields = buildJsonObject {
                put("status", "reading")
            },
        )

        record.isTombstone.shouldBeTrue()
        record.id shouldBe "title-1"
    }

    @Test
    fun `case 4 - document rejects record map keys that differ from stable IDs`() {
        shouldThrow<IllegalArgumentException> {
            SyncDocumentEnvelope(
                schemaVersion = 1,
                kind = SyncDocumentKind.LIBRARY,
                revision = revision,
                generatedAtEpochMillis = 100,
                records = mapOf(
                    "wrong-key" to SyncRecordEnvelope(
                        id = "title-1",
                        revision = revision,
                        updatedAtEpochMillis = 100,
                        fields = buildJsonObject {},
                    ),
                ),
            )
        }
    }

    @Test
    fun `case 5 - ordinary document envelope cannot masquerade as manifest`() {
        shouldThrow<IllegalArgumentException> {
            SyncDocumentEnvelope(
                schemaVersion = 1,
                kind = SyncDocumentKind.MANIFEST,
                revision = revision,
                generatedAtEpochMillis = 100,
                records = emptyMap(),
            )
        }
    }

    @Test
    fun `case 6 - manifest cannot recursively describe itself`() {
        shouldThrow<IllegalArgumentException> {
            SyncManifest(
                schemaVersion = 1,
                revision = revision,
                updatedAtEpochMillis = 100,
                documents = mapOf(
                    SyncDocumentKind.MANIFEST to SyncManifestEntry(
                        schemaVersion = 1,
                        revision = revision,
                        updatedAtEpochMillis = 100,
                        contentDigest = "abc123",
                    ),
                ),
            )
        }
    }

    @Test
    fun `case 7 - revision metadata requires stable device identity`() {
        shouldThrow<IllegalArgumentException> {
            SyncRevision(
                deviceId = " ",
                sequence = 1,
            )
        }
    }

    @Test
    fun `case 8 - remote revision remains transport opaque`() {
        val remote = SyncRemoteRevision(
            remoteId = "remote-file-id",
            revisionToken = "opaque-revision",
            modifiedAtEpochMillis = 42,
        )

        remote.revisionToken shouldBe "opaque-revision"
    }
}
