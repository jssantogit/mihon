package eu.kanade.tachiyomi.ui.tsuzuki.library

import tachiyomi.domain.category.model.Category
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.library.model.CanonicalLibraryItem
import tachiyomi.domain.tsuzuki.library.model.LOCAL_LIBRARY_ORIGIN
import tachiyomi.domain.tsuzuki.library.model.UnifiedLibraryTitle
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress

enum class CanonicalLibraryReadingState {
    NOT_STARTED,
    IN_PROGRESS,
    READ,
}

data class CanonicalLibraryFilterState(
    val status: LibraryStatus? = null,
    val origin: String? = null,
    val formats: Set<CatalogItemFormat> = emptySet(),
    val categoryId: Long? = null,
)

data class CanonicalLibraryCardModel(
    val canonicalTitleId: String,
    val title: String,
    val status: LibraryStatus,
    val categories: List<Category>,
    val readingState: CanonicalLibraryReadingState,
    val originStatuses: Map<String, Set<LibraryStatus>> = emptyMap(),
    val format: CatalogItemFormat = CatalogItemFormat.UNKNOWN,
    val hasLocalMembership: Boolean = true,
) {
    val origins: Set<String>
        get() = originStatuses.keys
}

internal fun UnifiedLibraryTitle.toCardModel(
    progress: List<CanonicalChapterProgress>,
): CanonicalLibraryCardModel {
    val readingState = progress.toReadingState()
    val originStatuses = buildMap<String, Set<LibraryStatus>> {
        localEntry?.status?.let { put(LOCAL_LIBRARY_ORIGIN, setOf(it)) }
        externalMemberships
            .groupBy { it.provider }
            .forEach { (provider, memberships) ->
                put(
                    provider,
                    memberships.mapNotNull { it.status }.toSet(),
                )
            }
    }
    val primaryStatus = localEntry?.status
        ?: externalMemberships.firstNotNullOfOrNull { it.status }
        ?: LibraryStatus.PLANNING

    return CanonicalLibraryCardModel(
        canonicalTitleId = title.id,
        title = title.displayTitle,
        status = primaryStatus,
        categories = categories,
        readingState = readingState,
        originStatuses = originStatuses,
        format = format,
        hasLocalMembership = localEntry != null,
    )
}

internal fun CanonicalLibraryItem.toCardModel(
    progress: List<CanonicalChapterProgress>,
): CanonicalLibraryCardModel = CanonicalLibraryCardModel(
    canonicalTitleId = title.id,
    title = title.displayTitle,
    status = entry.status,
    categories = categories,
    readingState = progress.toReadingState(),
    originStatuses = mapOf(LOCAL_LIBRARY_ORIGIN to setOf(entry.status)),
    format = CatalogItemFormat.UNKNOWN,
    hasLocalMembership = true,
)

internal fun filterCanonicalLibraryCards(
    items: List<CanonicalLibraryCardModel>,
    filters: CanonicalLibraryFilterState,
): List<CanonicalLibraryCardModel> = items.filter { item ->
    val originMatches = filters.origin == null || filters.origin in item.origins
    val statusMatches = filters.status == null ||
        item.originStatuses
            .asSequence()
            .filter { (origin, _) -> filters.origin == null || origin == filters.origin }
            .any { (_, statuses) -> filters.status in statuses }
    val formatMatches = filters.formats.isEmpty() || item.format in filters.formats
    val categoryMatches = when (filters.categoryId) {
        null -> true
        Category.UNCATEGORIZED_ID -> item.categories.isEmpty()
        else -> item.categories.any { it.id == filters.categoryId }
    }

    originMatches && statusMatches && formatMatches && categoryMatches
}

private fun List<CanonicalChapterProgress>.toReadingState(): CanonicalLibraryReadingState = when {
    any { !it.read && it.lastPageRead > 0L } -> CanonicalLibraryReadingState.IN_PROGRESS
    any(CanonicalChapterProgress::read) -> CanonicalLibraryReadingState.READ
    else -> CanonicalLibraryReadingState.NOT_STARTED
}
