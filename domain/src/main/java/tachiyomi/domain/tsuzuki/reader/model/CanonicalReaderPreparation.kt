package tachiyomi.domain.tsuzuki.reader.model

import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant

sealed interface CanonicalReaderPreparation {

    data class Ready(
        val target: OperationalReaderChapter,
        val usedFallback: Boolean,
    ) : CanonicalReaderPreparation

    data class FallbackRequired(
        val canonicalTitleId: String,
        val canonicalChapterId: String,
        val preferredSourceMappingId: String?,
        val fallbackVariant: ChapterVariant,
    ) : CanonicalReaderPreparation

    data class Unavailable(
        val canonicalChapterId: String,
    ) : CanonicalReaderPreparation

    data class Failed(
        val canonicalChapterId: String,
        val error: Throwable,
    ) : CanonicalReaderPreparation
}
