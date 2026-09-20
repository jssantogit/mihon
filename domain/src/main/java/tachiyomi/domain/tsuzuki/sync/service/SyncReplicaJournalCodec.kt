package tachiyomi.domain.tsuzuki.sync.service

import kotlinx.serialization.json.Json
import tachiyomi.domain.tsuzuki.sync.model.SyncCodecResult
import tachiyomi.domain.tsuzuki.sync.model.SyncReplicaJournal

class SyncReplicaJournalCodec(
    private val json: Json,
) {
    fun encode(journal: SyncReplicaJournal): SyncCodecResult<String> =
        error("Protocol v2 journal codec not implemented")

    fun decode(content: String): SyncCodecResult<SyncReplicaJournal> =
        error("Protocol v2 journal codec not implemented")
}
