package tachiyomi.domain.tsuzuki.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.metadata.ReportedChapterCount
import tachiyomi.domain.tsuzuki.metadata.repository.ReportedChapterCountRepository
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import kotlin.time.Clock

class MaterializeCanonicalTitleFromCatalog internal constructor(
    private val materializeCanonicalTitle: MaterializeCanonicalTitle,
    private val reportedChapterCountRepository: ReportedChapterCountRepository?,
    private val clock: () -> Long,
) {

    @Inject
    constructor(
        materializeCanonicalTitle: MaterializeCanonicalTitle,
        reportedChapterCountRepository: ReportedChapterCountRepository,
    ) : this(
        materializeCanonicalTitle = materializeCanonicalTitle,
        reportedChapterCountRepository = reportedChapterCountRepository,
        clock = { Clock.System.now().toEpochMilliseconds() },
    )

    internal constructor(
        materializeCanonicalTitle: MaterializeCanonicalTitle,
    ) : this(
        materializeCanonicalTitle = materializeCanonicalTitle,
        reportedChapterCountRepository = null,
        clock = { Clock.System.now().toEpochMilliseconds() },
    )

    suspend fun execute(catalogItem: CatalogItem): CanonicalTitle {
        val title = materializeCanonicalTitle.fromCatalog(
            displayTitle = catalogItem.title,
            provider = catalogItem.provider,
            externalId = catalogItem.providerId,
        )
        reportedChapterCountRepository?.upsert(
            ReportedChapterCount(
                canonicalTitleId = title.id,
                provider = catalogItem.provider,
                chapterCount = catalogItem.chapterCount?.takeIf { it > 0 },
                updatedAt = clock(),
            ),
        )
        return title
    }
}
