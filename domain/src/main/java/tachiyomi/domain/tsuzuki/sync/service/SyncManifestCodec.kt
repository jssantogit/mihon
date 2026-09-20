package tachiyomi.domain.tsuzuki.sync.service

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import tachiyomi.domain.tsuzuki.sync.model.SyncCodecResult
import tachiyomi.domain.tsuzuki.sync.model.SyncFailure
import tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason
import tachiyomi.domain.tsuzuki.sync.model.SyncManifest

interface SyncManifestCodec {
    fun encode(manifest: SyncManifest): SyncCodecResult<String>

    fun decode(content: String): SyncCodecResult<SyncManifest>
}

class KotlinxSyncManifestCodec(
    private val json: Json,
) : SyncManifestCodec {

    override fun encode(manifest: SyncManifest): SyncCodecResult<String> {
        return try {
            val element = json.encodeToJsonElement(
                SyncManifest.serializer(),
                manifest,
            )
            val canonical = canonicalizeSyncJson(element)
            SyncCodecResult.Success(
                json.encodeToString(JsonElement.serializer(), canonical),
            )
        } catch (_: SerializationException) {
            SyncCodecResult.Failure(
                SyncFailure(SyncFailureReason.MALFORMED_DOCUMENT),
            )
        } catch (_: IllegalArgumentException) {
            SyncCodecResult.Failure(
                SyncFailure(SyncFailureReason.MALFORMED_DOCUMENT),
            )
        }
    }

    override fun decode(content: String): SyncCodecResult<SyncManifest> {
        return try {
            SyncCodecResult.Success(
                json.decodeFromString<SyncManifest>(content),
            )
        } catch (_: SerializationException) {
            SyncCodecResult.Failure(
                SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT),
            )
        } catch (_: IllegalArgumentException) {
            SyncCodecResult.Failure(
                SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT),
            )
        }
    }
}
