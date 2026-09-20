package tachiyomi.domain.tsuzuki.sync.service

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import tachiyomi.domain.tsuzuki.sync.model.SyncCodecResult
import tachiyomi.domain.tsuzuki.sync.model.SyncFailure
import tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason
import tachiyomi.domain.tsuzuki.sync.model.SyncReplicaJournal

class SyncReplicaJournalCodec(
    private val json: Json,
) {
    fun encode(journal: SyncReplicaJournal): SyncCodecResult<String> {
        return try {
            val element = json.encodeToJsonElement(
                SyncReplicaJournal.serializer(),
                journal,
            )
            SyncCodecResult.Success(
                json.encodeToString(
                    JsonElement.serializer(),
                    canonicalizeSyncJson(element),
                ),
            )
        } catch (_: SerializationException) {
            SyncCodecResult.Failure(SyncFailure(SyncFailureReason.MALFORMED_DOCUMENT))
        } catch (_: IllegalArgumentException) {
            SyncCodecResult.Failure(SyncFailure(SyncFailureReason.MALFORMED_DOCUMENT))
        }
    }

    fun decode(content: String): SyncCodecResult<SyncReplicaJournal> {
        return try {
            SyncCodecResult.Success(
                json.decodeFromString<SyncReplicaJournal>(content),
            )
        } catch (_: SerializationException) {
            SyncCodecResult.Failure(SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT))
        } catch (_: IllegalArgumentException) {
            SyncCodecResult.Failure(SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT))
        }
    }
}
