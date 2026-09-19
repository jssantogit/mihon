package tachiyomi.domain.tsuzuki.sync

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.sync.model.SyncCodecResult
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncManifest
import tachiyomi.domain.tsuzuki.sync.model.SyncManifestEntry
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision
import tachiyomi.domain.tsuzuki.sync.service.KotlinxSyncManifestCodec
import tachiyomi.domain.tsuzuki.sync.service.Sha256SyncContentDigest

class SyncManifestCodecTest {

    private val codec = KotlinxSyncManifestCodec(Json)

    @Test
    fun `case 1 - manifest encoding is canonical regardless of map insertion order`() {
        val library = SyncManifestEntry(
            schemaVersion = 1,
            revision = SyncRevision("phone", 2),
            updatedAtEpochMillis = 20,
            contentDigest = "library-digest",
        )
        val mappings = SyncManifestEntry(
            schemaVersion = 1,
            revision = SyncRevision("phone", 3),
            updatedAtEpochMillis = 30,
            contentDigest = "mapping-digest",
        )
        val first = manifest(
            linkedMapOf(
                SyncDocumentKind.SOURCE_MAPPINGS to mappings,
                SyncDocumentKind.LIBRARY to library,
            ),
        )
        val second = manifest(
            linkedMapOf(
                SyncDocumentKind.LIBRARY to library,
                SyncDocumentKind.SOURCE_MAPPINGS to mappings,
            ),
        )

        encode(first) shouldBe encode(second)
    }

    @Test
    fun `case 2 - sha256 content digest is deterministic and fixed width`() {
        val digest = Sha256SyncContentDigest()

        digest.digest("tsuzuki") shouldBe digest.digest("tsuzuki")
        digest.digest("tsuzuki").shouldHaveLength(64)
    }

    private fun manifest(
        documents: Map<SyncDocumentKind, SyncManifestEntry>,
    ) = SyncManifest(
        schemaVersion = 1,
        revision = SyncRevision("phone", 10),
        updatedAtEpochMillis = 100,
        documents = documents,
    )

    private fun encode(manifest: SyncManifest): String {
        return when (val encoded = codec.encode(manifest)) {
            is SyncCodecResult.Success -> encoded.value
            is SyncCodecResult.Failure -> error("manifest encode failed")
        }
    }
}
