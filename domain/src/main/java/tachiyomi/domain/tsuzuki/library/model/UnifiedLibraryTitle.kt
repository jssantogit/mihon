package tachiyomi.domain.tsuzuki.library.model

import tachiyomi.domain.category.model.Category
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.model.SourceRepresentation

const val LOCAL_LIBRARY_ORIGIN = "tsuzuki"

data class UnifiedLibraryTitle(
    val title: CanonicalTitle,
    val localEntry: CanonicalLibraryEntry?,
    val externalMemberships: List<ExternalLibraryMembership>,
    val sources: List<SourceRepresentation> = emptyList(),
    val categories: List<Category> = emptyList(),
    val format: CatalogItemFormat = CatalogItemFormat.UNKNOWN,
) {
    val id: String
        get() = title.id

    val origins: Set<String>
        get() = buildSet {
            if (localEntry != null) add(LOCAL_LIBRARY_ORIGIN)
            externalMemberships.mapTo(this, ExternalLibraryMembership::provider)
        }

    fun statusesForOrigin(origin: String?): Set<LibraryStatus> = buildSet {
        if (origin == null || origin == LOCAL_LIBRARY_ORIGIN) {
            localEntry?.status?.let(::add)
        }
        externalMemberships
            .asSequence()
            .filter { origin == null || it.provider == origin }
            .mapNotNull(ExternalLibraryMembership::status)
            .forEach(::add)
    }
}

fun projectUnifiedLibrary(
    titles: List<CanonicalTitle>,
    localEntries: List<CanonicalLibraryEntry>,
    externalMemberships: List<ExternalLibraryMembership>,
    formatObservations: List<TitleFormatObservation>,
    sources: List<SourceRepresentation> = emptyList(),
    categories: List<LibraryTitleCategory> = emptyList(),
): List<UnifiedLibraryTitle> {
    val localByTitle = localEntries.associateBy(CanonicalLibraryEntry::canonicalTitleId)
    val externalByTitle = externalMemberships.groupBy(ExternalLibraryMembership::canonicalTitleId)
    val formatsByTitle = formatObservations.groupBy(TitleFormatObservation::canonicalTitleId)
    val sourcesByTitle = sources.groupBy(SourceRepresentation::canonicalTitleId)
    val categoriesByTitle = categories.groupBy(LibraryTitleCategory::canonicalTitleId)

    return titles
        .asSequence()
        .filter { title ->
            title.id in localByTitle || !externalByTitle[title.id].isNullOrEmpty()
        }
        .map { title ->
            UnifiedLibraryTitle(
                title = title,
                localEntry = localByTitle[title.id],
                externalMemberships = externalByTitle[title.id].orEmpty()
                    .sortedWith(
                        compareBy(
                            ExternalLibraryMembership::provider,
                            ExternalLibraryMembership::listKey,
                            ExternalLibraryMembership::externalId,
                        ),
                    ),
                sources = sourcesByTitle[title.id].orEmpty(),
                categories = categoriesByTitle[title.id].orEmpty().map(LibraryTitleCategory::category),
                format = resolveLibraryFormat(formatsByTitle[title.id].orEmpty()),
            )
        }
        .sortedByDescending { item ->
            maxOf(
                item.localEntry?.addedAt ?: Long.MIN_VALUE,
                item.externalMemberships.maxOfOrNull { membership ->
                    membership.listedAt ?: membership.syncedAt
                } ?: Long.MIN_VALUE,
            )
        }
        .toList()
}

private fun resolveLibraryFormat(
    observations: List<TitleFormatObservation>,
): CatalogItemFormat {
    return observations
        .asSequence()
        .filter { it.format != CatalogItemFormat.UNKNOWN }
        .sortedWith(
            compareBy<TitleFormatObservation>(
                { observation ->
                    FORMAT_PROVIDER_PRECEDENCE.indexOf(observation.provider)
                        .takeIf { it >= 0 }
                        ?: Int.MAX_VALUE
                },
                { it.provider },
                { -it.updatedAt },
            ),
        )
        .map(TitleFormatObservation::format)
        .firstOrNull()
        ?: CatalogItemFormat.UNKNOWN
}

private val FORMAT_PROVIDER_PRECEDENCE = listOf(
    "mangaupdates",
    "mal",
    "kitsu",
    "bangumi",
)
