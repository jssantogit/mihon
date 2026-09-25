package tachiyomi.domain.tsuzuki.addon

import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
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
