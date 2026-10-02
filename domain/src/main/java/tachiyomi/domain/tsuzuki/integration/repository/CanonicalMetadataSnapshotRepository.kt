package tachiyomi.domain.tsuzuki.integration.repository

import tachiyomi.domain.tsuzuki.integration.model.ResolvedMetadata

data class CanonicalMetadataSnapshot(
    val canonicalTitleId: String,
    val configurationFingerprint: String,
    val metadata: ResolvedMetadata,
    val refreshedAt: Long,
)

interface CanonicalMetadataSnapshotRepository {
    suspend fun get(canonicalTitleId: String): CanonicalMetadataSnapshot?

    suspend fun upsertIfNewer(snapshot: CanonicalMetadataSnapshot): CanonicalMetadataSnapshot

    suspend fun invalidateTitle(canonicalTitleId: String)
}
