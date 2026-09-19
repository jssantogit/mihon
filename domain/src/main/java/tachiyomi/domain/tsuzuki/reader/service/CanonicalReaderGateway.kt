package tachiyomi.domain.tsuzuki.reader.service

import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.model.OperationalReaderChapter

/**
 * Compatibility boundary that projects one Tsuzuki variant into the existing
 * Mihon Reader model only when reading requires it.
 */
interface CanonicalReaderGateway {
    suspend fun materialize(
        variant: ChapterVariant,
        progress: CanonicalChapterProgress?,
    ): Result<OperationalReaderChapter>
}
