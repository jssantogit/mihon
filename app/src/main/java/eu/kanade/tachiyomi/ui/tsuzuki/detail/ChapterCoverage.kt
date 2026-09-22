package eu.kanade.tachiyomi.ui.tsuzuki.detail

import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter

/**
 * Summarizes only observed chapter identities. Provider-reported counts are metadata,
 * and cannot be used to infer or fabricate missing chapter rows.
 */
internal fun firstDiscoveredChapterNumber(chapters: List<CanonicalChapter>): Int? =
    chapters.mapNotNull { it.baseNumber }
        .filter { it >= 0 }
        .minOrNull()
