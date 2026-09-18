package tachiyomi.domain.tsuzuki.model

data class CanonicalLibraryEntry(
    val canonicalTitleId: String,
    val status: LibraryStatus,
    val favorite: Boolean,
    val addedAt: Long,
    val updatedAt: Long,
)
