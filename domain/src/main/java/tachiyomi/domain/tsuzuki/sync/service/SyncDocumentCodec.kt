package tachiyomi.domain.tsuzuki.sync.service

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import tachiyomi.domain.tsuzuki.sync.model.SyncCodecResult
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncFailure
import tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason

interface SyncDocumentCodec {
    fun encode(document: SyncDocumentEnvelope): SyncCodecResult<String>

    fun decode(content: String): SyncCodecResult<SyncDocumentEnvelope>
}

class KotlinxSyncDocumentCodec(
    private val json: Json,
) : SyncDocumentCodec {

    override fun encode(document: SyncDocumentEnvelope): SyncCodecResult<String> {
        return try {
            val element = json.encodeToJsonElement(
                SyncDocumentEnvelope.serializer(),
                document,
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

    override fun decode(content: String): SyncCodecResult<SyncDocumentEnvelope> {
        return try {
            SyncCodecResult.Success(
                json.decodeFromString<SyncDocumentEnvelope>(content),
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
