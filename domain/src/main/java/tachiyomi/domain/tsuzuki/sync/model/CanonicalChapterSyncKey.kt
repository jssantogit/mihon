package tachiyomi.domain.tsuzuki.sync.model

import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant

data class CanonicalChapterSyncKey(
    val canonicalTitleId: String,
    val chapterKey: String,
) {
    init {
        require(canonicalTitleId.isNotBlank()) { "Canonical title ID must not be blank" }
        require(chapterKey.isNotBlank()) { "Canonical chapter sync key must not be blank" }
    }

    val recordId: String
        get() = lengthPrefixed(canonicalTitleId) + lengthPrefixed(chapterKey)

    companion object {
        fun from(
            chapter: CanonicalChapter,
            stableEvidenceKey: String? = null,
        ): CanonicalChapterSyncKey? {
            val identity = chapter.identity
            val chapterKey = when {
                identity.isNumbered || identity.isSpecific -> {
                    "identity:${identity.sortKey}"
                }
                !stableEvidenceKey.isNullOrBlank() -> {
                    "evidence:${stableEvidenceKey.trim()}"
                }
                else -> return null
            }

            return CanonicalChapterSyncKey(
                canonicalTitleId = chapter.canonicalTitleId,
                chapterKey = chapterKey,
            )
        }

        private fun lengthPrefixed(value: String): String =
            "${value.length}:$value"
    }
}

object CanonicalVariantSyncKey {
    fun from(variant: ChapterVariant): String? {
        if (variant.sourceId == 0L || variant.sourceChapterId.isBlank()) return null
        return buildString {
            append("source:")
            append(variant.sourceId)
            append(':')
            append(variant.sourceChapterId.length)
            append(':')
            append(variant.sourceChapterId)
        }
    }
}
