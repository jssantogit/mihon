package tachiyomi.domain.tsuzuki.reader.model

sealed interface CanonicalReadingStart {
    data class Ready(
        val canonicalChapterId: String,
    ) : CanonicalReadingStart

    data class Unavailable(
        val canonicalTitleId: String,
        val reason: Throwable? = null,
    ) : CanonicalReadingStart
}
