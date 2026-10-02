package tachiyomi.domain.tsuzuki.download.repository

import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.download.model.CanonicalDownloadArtifact

interface CanonicalDownloadRepository {
    suspend fun get(canonicalChapterId: String): CanonicalDownloadArtifact?
    suspend fun getAll(): List<CanonicalDownloadArtifact> = emptyList()

    /**
     * Production repositories should answer this without hydrating unrelated titles.
     * The fallback keeps lightweight test fakes source-compatible.
     */
    suspend fun getChapterIdsByCanonicalTitle(canonicalTitleId: String): Set<String> =
        getAll().mapTo(linkedSetOf()) { it.canonicalChapterId }
    suspend fun upsert(artifact: CanonicalDownloadArtifact)
    suspend fun delete(canonicalChapterId: String)
    suspend fun deleteOriginMetadata(addonId: AddonId)
}
