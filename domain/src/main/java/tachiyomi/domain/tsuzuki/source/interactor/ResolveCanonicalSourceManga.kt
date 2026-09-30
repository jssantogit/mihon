package tachiyomi.domain.tsuzuki.source.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceRepresentation
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceCandidate
import tachiyomi.domain.tsuzuki.source.service.ReadingSourceGateway

@Inject
class ResolveCanonicalSourceManga(
    private val canonicalTitleRepository: CanonicalTitleRepository,
    private val sourceTitleMappingRepository: SourceTitleMappingRepository,
    private val mangaRepository: MangaRepository,
    private val networkToLocalManga: NetworkToLocalManga,
    private val readingSourceGateway: ReadingSourceGateway,
) {

    suspend fun execute(canonicalTitleId: String): Manga? {
        val diagnosticId = canonicalTitleId.take(8)
        val title = canonicalTitleRepository.getById(canonicalTitleId)
        if (title == null) {
            logcat(LogPriority.WARN) { "TsuzukiCover sourceResolve title=$diagnosticId missingCanonicalTitle=true" }
            return null
        }
        val mappings = sourceTitleMappingRepository
            .getByCanonicalTitleId(canonicalTitleId)
            .filter { it.availability != SourceMappingAvailability.UNAVAILABLE }
            .sortedWith(
                compareByDescending<SourceRepresentation> { it.preferredOverride }
                    .thenByDescending { it.updatedAt },
            )

        logcat { "TsuzukiCover sourceResolve title=$diagnosticId mappings=${mappings.size}" }

        var fallback: Manga? = null
        for (mapping in mappings) {
            val persisted = findPersistedManga(mapping)
            logcat {
                "TsuzukiCover sourceResolve title=$diagnosticId source=${mapping.sourceId} " +
                    "persisted=${persisted != null} initialized=${persisted?.initialized == true} " +
                    "thumbnail=${!persisted?.thumbnailUrl.isNullOrBlank()} mappedMihonId=${mapping.mihonMangaId != null}"
            }
            if (!persisted?.thumbnailUrl.isNullOrBlank() && persisted.initialized) {
                logcat {
                    "TsuzukiCover sourceResolve title=$diagnosticId source=${mapping.sourceId} selected=persisted"
                }
                return persisted
            }
            if (fallback == null && persisted != null) {
                fallback = persisted
            }

            val candidate = ReadingSourceCandidate(
                sourceId = mapping.sourceId,
                sourceName = "Source ${mapping.sourceId}",
                language = mapping.language,
                sourceUrl = mapping.sourceUrl,
                title = persisted?.title?.takeIf { it.isNotBlank() } ?: title.displayTitle,
                thumbnailUrl = persisted?.thumbnailUrl,
                author = persisted?.author,
                artist = persisted?.artist,
                description = persisted?.description,
                genres = persisted?.genre,
                status = persisted?.status ?: 0L,
            )
            val detailsResult = try {
                readingSourceGateway.getDetails(candidate)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logcat(LogPriority.WARN, error) {
                    "TsuzukiCover sourceResolve title=$diagnosticId source=${mapping.sourceId} detailsThrown=true"
                }
                null
            }
            val details = detailsResult?.getOrNull()
            logcat {
                "TsuzukiCover sourceResolve title=$diagnosticId source=${mapping.sourceId} " +
                    "details=${details != null} detailsThumbnail=${!details?.thumbnailUrl.isNullOrBlank()}"
            }
            if (details == null) continue

            val enriched = (persisted ?: Manga.create()).copy(
                source = mapping.sourceId,
                url = mapping.sourceUrl,
                title = details.title.takeIf { it.isNotBlank() }
                    ?: persisted?.title
                    ?: title.displayTitle,
                thumbnailUrl = details.thumbnailUrl
                    ?.takeIf { it.isNotBlank() }
                    ?: persisted?.thumbnailUrl,
                author = details.author ?: persisted?.author,
                artist = details.artist ?: persisted?.artist,
                description = details.description ?: persisted?.description,
                genre = details.genres ?: persisted?.genre,
                status = details.status.takeIf { it != 0L } ?: persisted?.status ?: 0L,
                initialized = true,
            )
            val repaired = try {
                networkToLocalManga(enriched)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
            if (repaired != null) {
                if (fallback == null) fallback = repaired
                if (!repaired.thumbnailUrl.isNullOrBlank()) {
                    logcat {
                        "TsuzukiCover sourceResolve title=$diagnosticId source=${mapping.sourceId} selected=repaired"
                    }
                    return repaired
                }
            }
        }

        logcat {
            "TsuzukiCover sourceResolve title=$diagnosticId selected=fallback " +
                "fallbackPresent=${fallback != null} fallbackThumbnail=${!fallback?.thumbnailUrl.isNullOrBlank()}"
        }
        return fallback
    }

    private suspend fun findPersistedManga(mapping: SourceRepresentation): Manga? {
        val byId = mapping.mihonMangaId?.let { mangaId ->
            try {
                mangaRepository.getMangaById(mangaId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
        }
        if (byId != null) return byId

        return try {
            mangaRepository.getMangaByUrlAndSourceId(mapping.sourceUrl, mapping.sourceId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
    }
}
