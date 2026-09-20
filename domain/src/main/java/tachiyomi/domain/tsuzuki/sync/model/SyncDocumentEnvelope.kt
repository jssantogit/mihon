package tachiyomi.domain.tsuzuki.sync.model

import kotlinx.serialization.Serializable

@Serializable
data class SyncDocumentEnvelope(
    val schemaVersion: Int,
    val kind: SyncDocumentKind,
    val revision: SyncRevision,
    val generatedAtEpochMillis: Long,
    val records: Map<String, SyncRecordEnvelope>,
) {
    init {
        require(schemaVersion >= 1) { "Sync document schema version must be positive" }
        require(kind != SyncDocumentKind.MANIFEST) {
            "Manifest uses SyncManifest rather than SyncDocumentEnvelope"
        }
        require(generatedAtEpochMillis >= 0) { "Sync document generatedAt must not be negative" }
        require(records.all { (key, record) -> key == record.id }) {
            "Sync document record keys must match stable record IDs"
        }
    }
}
