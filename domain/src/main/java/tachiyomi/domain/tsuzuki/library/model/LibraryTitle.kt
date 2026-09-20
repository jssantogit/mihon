package tachiyomi.domain.tsuzuki.library.model

import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.SourceRepresentation

data class LibraryTitle(
    val title: CanonicalTitle,
    val entry: CanonicalLibraryEntry,
    val sources: List<SourceRepresentation> = emptyList(),
) {
    val id: String
        get() = title.id

    init {
        require(entry.canonicalTitleId == id) {
            "Library membership must belong to canonical title $id"
        }
        require(sources.all { it.canonicalTitleId == id }) {
            "Every source representation must belong to canonical title $id"
        }
    }
}
