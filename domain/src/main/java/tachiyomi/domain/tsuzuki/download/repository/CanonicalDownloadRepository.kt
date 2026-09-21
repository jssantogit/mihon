package tachiyomi.domain.tsuzuki.download.repository

import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.download.model.CanonicalDownloadArtifact

interface CanonicalDownloadRepository {
    suspend fun get(canonicalChapterId: String): CanonicalDownloadArtifact?
    suspend fun upsert(artifact: CanonicalDownloadArtifact)
    suspend fun delete(canonicalChapterId: String)
    suspend fun deleteOriginMetadata(addonId: AddonId)
}
