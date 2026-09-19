package tachiyomi.domain.tsuzuki.reader.model

data class CanonicalReaderPreference(
    val canonicalTitleId: String,
    val automaticFallback: Boolean = false,
    val updatedAt: Long = 0L,
)
