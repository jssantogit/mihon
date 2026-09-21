package eu.kanade.tachiyomi.ui.reader

import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress

internal fun resolveCanonicalLocalRequestedPage(
    resetPage: Boolean,
    savedPageIndex: Int,
    progress: CanonicalChapterProgress?,
): Int {
    if (resetPage) return 0
    if (savedPageIndex >= 0) return savedPageIndex
    return progress?.lastPageRead
        ?.coerceIn(0L, Int.MAX_VALUE.toLong())
        ?.toInt()
        ?: 0
}

internal fun ReaderViewModel.State.needsViewerInitialization(): Boolean {
    return viewer == null && (manga != null || viewerChapters != null)
}
