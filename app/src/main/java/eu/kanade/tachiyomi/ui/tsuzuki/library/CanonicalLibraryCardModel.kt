package eu.kanade.tachiyomi.ui.tsuzuki.library

import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.model.MangaCover
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
    val listKey: String? = null,
    val formats: Set<CatalogItemFormat> = emptySet(),
    val categoryId: Long? = null,
)

data class CanonicalLibraryListFilterOption(
    val key: String,
    val title: String,
    val selectionGroup: String? = null,
)

data class CanonicalLibraryCardModel(
    val canonicalTitleId: String,
    val title: String,
    val status: LibraryStatus,
    val categories: List<Category>,
    val readingState: CanonicalLibraryReadingState,
    val originStatuses: Map<String, Set<LibraryStatus>> = emptyMap(),
    val originListKeys: Map<String, Set<String>> = emptyMap(),
    val originListOptions: Map<String, Set<CanonicalLibraryListFilterOption>> = emptyMap(),
    val format: CatalogItemFormat = CatalogItemFormat.UNKNOWN,
    val hasLocalMembership: Boolean = true,
    val coverUrl: String? = null,
    val sourceCover: MangaCover? = null,
    val localCoverUrl: String? = null,
) {
    val coverData: Any?
        get() = coverUrl ?: sourceCover ?: localCoverUrl

    val origins: Set<String>
        get() = originStatuses.keys + originListKeys.keys
}

internal fun UnifiedLibraryTitle.toCardModel(
    progress: List<CanonicalChapterProgress>,
    localCoverUrl: String? = null,
    sourceCover: MangaCover? = null,
    canonicalCoverUrl: String? = null,
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
    val originListKeys = externalMemberships
        .groupBy { it.provider }
        .mapValues { (_, memberships) ->
            memberships.map { it.listKey }.toSet()
        }
    val originListOptions = externalMemberships
        .groupBy { it.provider }
        .mapValues { (_, memberships) ->
            memberships.mapNotNull { membership ->
                val title = membership.listTitle?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                CanonicalLibraryListFilterOption(
                    key = membership.listKey,
                    title = title,
                    selectionGroup = membership.selectionGroup,
                )
            }.toSet()
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
        originListKeys = originListKeys,
        originListOptions = originListOptions,
        format = format,
        hasLocalMembership = localEntry != null,
        coverUrl = canonicalCoverUrl ?: preferredExternalCoverUrl(),
        sourceCover = sourceCover,
        localCoverUrl = localCoverUrl,
    )
}

internal fun CanonicalLibraryItem.toCardModel(
    progress: List<CanonicalChapterProgress>,
    localCoverUrl: String? = null,
): CanonicalLibraryCardModel = CanonicalLibraryCardModel(
    canonicalTitleId = title.id,
    title = title.displayTitle,
    status = entry.status,
    categories = categories,
    readingState = progress.toReadingState(),
    originStatuses = mapOf(LOCAL_LIBRARY_ORIGIN to setOf(entry.status)),
    format = CatalogItemFormat.UNKNOWN,
    hasLocalMembership = true,
    localCoverUrl = localCoverUrl,
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
    val listMatches = filters.listKey == null ||
        item.originListKeys
            .asSequence()
            .filter { (origin, _) -> filters.origin == null || origin == filters.origin }
            .any { (_, listKeys) -> filters.listKey in listKeys }
    val formatMatches = filters.formats.isEmpty() || item.format in filters.formats
    val categoryMatches = when (filters.categoryId) {
        null -> true
        Category.UNCATEGORIZED_ID -> item.categories.isEmpty()
        else -> item.categories.any { it.id == filters.categoryId }
    }

    originMatches && statusMatches && listMatches && formatMatches && categoryMatches
}

private fun List<CanonicalChapterProgress>.toReadingState(): CanonicalLibraryReadingState = when {
    any { !it.read && it.lastPageRead > 0L } -> CanonicalLibraryReadingState.IN_PROGRESS
    any(CanonicalChapterProgress::read) -> CanonicalLibraryReadingState.READ
    else -> CanonicalLibraryReadingState.NOT_STARTED
}

internal fun availableProviderListFilters(
    items: List<CanonicalLibraryCardModel>,
    origin: String?,
): List<CanonicalLibraryListFilterOption> {
    if (origin == null || origin == LOCAL_LIBRARY_ORIGIN) return emptyList()

    return items
        .asSequence()
        .flatMap { item -> item.originListOptions[origin].orEmpty().asSequence() }
        .filterNot { option -> option.selectionGroup?.endsWith(":status") == true }
        .distinctBy(CanonicalLibraryListFilterOption::key)
        .sortedWith(
            compareBy(
                { it.title.lowercase() },
                CanonicalLibraryListFilterOption::key,
            ),
        )
        .toList()
}

internal fun UnifiedLibraryTitle.preferredExternalCoverUrl(): String? =
    externalMemberships
        .asSequence()
        .filter { !it.coverUrl.isNullOrBlank() }
        .sortedWith(
            compareBy(
                { membership ->
                    LIBRARY_ARTWORK_PROVIDER_PRECEDENCE.indexOf(membership.provider)
                        .takeIf { it >= 0 }
                        ?: Int.MAX_VALUE
                },
                { it.provider },
                { it.externalId },
            ),
        )
        .mapNotNull { it.coverUrl }
        .firstOrNull()

private val LIBRARY_ARTWORK_PROVIDER_PRECEDENCE = listOf(
    "kitsu",
    "mal",
    "mangaupdates",
    "bangumi",
)
