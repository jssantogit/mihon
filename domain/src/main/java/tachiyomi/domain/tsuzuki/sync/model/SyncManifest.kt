package tachiyomi.domain.tsuzuki.sync.model

import kotlinx.serialization.Serializable

@Serializable
data class SyncManifest(
    val schemaVersion: Int,
    val revision: SyncRevision,
    val updatedAtEpochMillis: Long,
    val documents: Map<SyncDocumentKind, SyncManifestEntry>,
) {
    init {
        require(schemaVersion >= 1) { "Sync manifest schema version must be positive" }
        require(updatedAtEpochMillis >= 0) { "Sync manifest updatedAt must not be negative" }
        require(SyncDocumentKind.MANIFEST !in documents) {
            "Sync manifest must not contain itself as a logical document"
        }
    }
}

@Serializable
data class SyncManifestEntry(
    val schemaVersion: Int,
    val revision: SyncRevision,
    val updatedAtEpochMillis: Long,
    val contentDigest: String,
) {
    init {
        require(schemaVersion >= 1) { "Sync manifest entry schema version must be positive" }
        require(updatedAtEpochMillis >= 0) { "Sync manifest entry updatedAt must not be negative" }
        require(contentDigest.isNotBlank()) { "Sync manifest entry digest must not be blank" }
    }
}
