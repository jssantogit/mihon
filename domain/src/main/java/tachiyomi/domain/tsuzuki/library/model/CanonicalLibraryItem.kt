package tachiyomi.domain.tsuzuki.library.model

import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.CanonicalTitle

data class CanonicalLibraryItem(
    val title: CanonicalTitle,
    val entry: CanonicalLibraryEntry,
)
