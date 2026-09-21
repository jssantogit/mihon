package tachiyomi.domain.tsuzuki.addon

import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
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
