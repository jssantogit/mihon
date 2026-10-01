package tachiyomi.domain.tsuzuki.addon

import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.refresh.ChapterRefreshSnapshot
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentOption

typealias AddonId = tachiyomi.domain.tsuzuki.capability.AddonId

interface ContentProvider {
    val addonId: AddonId

    suspend fun resolve(
        canonicalTitleId: String,
        canonicalChapterId: String,
    ): Result<List<ContentOption>>
}

interface ChapterProbeProvider {
    val addonId: AddonId

    suspend fun probe(canonicalTitleId: String): Result<List<ChapterEvidence>>
}

data class ChapterProbeRefresh(
    val evidence: List<ChapterEvidence>,
    val observedBindingCount: Int = 0,
    val observedChapterCount: Int = evidence.size,
    val unchangedBindingCount: Int = 0,
    val pendingSnapshots: List<ChapterRefreshSnapshot> = emptyList(),
)

interface RefreshAwareChapterProbeProvider : ChapterProbeProvider {
    suspend fun probeRefresh(canonicalTitleId: String): Result<ChapterProbeRefresh>

    suspend fun refreshConfigurationFingerprint(): String = addonId.value
}

/** Fetch just one verified edition without probing sibling internal sources. */
interface TargetedChapterProbeProvider : ChapterProbeProvider {
    suspend fun probeBinding(binding: ContentBinding): Result<List<ChapterEvidence>>
}

interface TargetedContentProvider : ContentProvider {
    suspend fun resolveBinding(
        binding: ContentBinding,
        canonicalChapterId: String,
    ): Result<List<ContentOption>>
}
