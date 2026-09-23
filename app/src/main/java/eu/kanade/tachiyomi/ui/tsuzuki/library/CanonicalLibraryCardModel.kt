package eu.kanade.tachiyomi.ui.tsuzuki.library

import tachiyomi.domain.category.model.Category
import tachiyomi.domain.tsuzuki.library.model.CanonicalLibraryItem
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress

enum class CanonicalLibraryReadingState {
    NOT_STARTED,
    IN_PROGRESS,
    READ,
}

data class CanonicalLibraryCardModel(
    val canonicalTitleId: String,
    val title: String,
    val status: LibraryStatus,
    val categories: List<Category>,
    val readingState: CanonicalLibraryReadingState,
)

internal fun CanonicalLibraryItem.toCardModel(
    progress: List<CanonicalChapterProgress>,
): CanonicalLibraryCardModel {
    val readingState = when {
        progress.any { !it.read && it.lastPageRead > 0L } ->
            CanonicalLibraryReadingState.IN_PROGRESS
        progress.any(CanonicalChapterProgress::read) ->
            CanonicalLibraryReadingState.READ
        else ->
            CanonicalLibraryReadingState.NOT_STARTED
    }

    return CanonicalLibraryCardModel(
        canonicalTitleId = title.id,
        title = title.displayTitle,
        status = entry.status,
        categories = categories,
        readingState = readingState,
    )
}
