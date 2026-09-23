package tachiyomi.domain.tsuzuki.reader.service

import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.model.OperationalReaderChapter

/**
 * Compatibility boundary that projects already-selected Mihon operational
 * coordinates into the existing Reader state.
 */
interface CanonicalReaderGateway {
    suspend fun materialize(
        canonicalChapterId: String,
        delivery: ContentDelivery.Mihon,
        progress: CanonicalChapterProgress?,
    ): Result<OperationalReaderChapter>
}
