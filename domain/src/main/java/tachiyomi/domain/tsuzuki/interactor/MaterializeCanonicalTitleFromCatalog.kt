package tachiyomi.domain.tsuzuki.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.artwork.model.TitleArtworkObservation
import tachiyomi.domain.tsuzuki.artwork.repository.TitleArtworkRepository
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.library.model.TitleFormatObservation
import tachiyomi.domain.tsuzuki.metadata.ReportedChapterCount
import tachiyomi.domain.tsuzuki.metadata.repository.ReportedChapterCountRepository
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.repository.TitleFormatObservationRepository
import kotlin.time.Clock

class MaterializeCanonicalTitleFromCatalog internal constructor(
    private val materializeCanonicalTitle: MaterializeCanonicalTitle,
    private val reportedChapterCountRepository: ReportedChapterCountRepository?,
    private val clock: () -> Long,
    private val titleFormatObservationRepository: TitleFormatObservationRepository? = null,
    private val titleArtworkRepository: TitleArtworkRepository? = null,
) {

    @Inject
    constructor(
        materializeCanonicalTitle: MaterializeCanonicalTitle,
        reportedChapterCountRepository: ReportedChapterCountRepository,
        titleFormatObservationRepository: TitleFormatObservationRepository,
        titleArtworkRepository: TitleArtworkRepository,
    ) : this(
        materializeCanonicalTitle = materializeCanonicalTitle,
        reportedChapterCountRepository = reportedChapterCountRepository,
        clock = { Clock.System.now().toEpochMilliseconds() },
        titleFormatObservationRepository = titleFormatObservationRepository,
        titleArtworkRepository = titleArtworkRepository,
    )

    constructor(
        materializeCanonicalTitle: MaterializeCanonicalTitle,
        reportedChapterCountRepository: ReportedChapterCountRepository,
    ) : this(
        materializeCanonicalTitle = materializeCanonicalTitle,
        reportedChapterCountRepository = reportedChapterCountRepository,
        clock = { Clock.System.now().toEpochMilliseconds() },
        titleFormatObservationRepository = null,
    )

    internal constructor(
        materializeCanonicalTitle: MaterializeCanonicalTitle,
    ) : this(
        materializeCanonicalTitle = materializeCanonicalTitle,
        reportedChapterCountRepository = null,
        clock = { Clock.System.now().toEpochMilliseconds() },
        titleFormatObservationRepository = null,
    )

    suspend fun execute(catalogItem: CatalogItem): CanonicalTitle {
        val title = materializeCanonicalTitle.fromCatalog(
            displayTitle = catalogItem.title,
            provider = catalogItem.provider,
            externalId = catalogItem.providerId,
            externalIds = catalogItem.externalIds,
        )
        val now = clock()
        reportedChapterCountRepository?.upsert(
            ReportedChapterCount(
                canonicalTitleId = title.id,
                provider = catalogItem.provider,
                chapterCount = catalogItem.chapterCount?.takeIf { it > 0 },
                updatedAt = now,
            ),
        )
        if (catalogItem.format != CatalogItemFormat.UNKNOWN) {
            titleFormatObservationRepository?.upsert(
                TitleFormatObservation(
                    canonicalTitleId = title.id,
                    provider = catalogItem.provider,
                    format = catalogItem.format,
                    updatedAt = now,
                ),
            )
        }
        val coverUrl = catalogItem.coverUrl?.takeIf(String::isNotBlank)
        val bannerUrl = catalogItem.bannerUrl?.takeIf(String::isNotBlank)
        if (coverUrl != null || bannerUrl != null) {
            titleArtworkRepository?.upsert(
                TitleArtworkObservation(
                    canonicalTitleId = title.id,
                    provider = catalogItem.provider,
                    coverUrl = coverUrl,
                    bannerUrl = bannerUrl,
                    updatedAt = now,
                ),
            )
        }
        return title
    }
}
