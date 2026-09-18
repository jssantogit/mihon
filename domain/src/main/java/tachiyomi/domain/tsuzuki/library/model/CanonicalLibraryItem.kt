package tachiyomi.domain.tsuzuki.library.model

import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping

data class CanonicalLibraryItem(
    val title: CanonicalTitle,
    val entry: CanonicalLibraryEntry,
    val primaryMapping: SourceTitleMapping? = null,
)
