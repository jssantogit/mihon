package tachiyomi.domain.tsuzuki.download.model

import tachiyomi.domain.tsuzuki.addon.AddonId

data class CanonicalDownloadArtifact(
    val canonicalChapterId: String,
    val localUri: String,
    val format: String,
    val originatingAddonId: AddonId?,
    val originatingOptionKey: String?,
    val completedAt: Long,
    val checksum: String?,
)
