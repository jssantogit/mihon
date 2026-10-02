package tachiyomi.domain.tsuzuki.source.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentBindingAvailability
import tachiyomi.domain.tsuzuki.content.repository.ContentBindingRepository
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttribute
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticInvariantCode
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSeverity
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticTrace
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticWorkflow
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder
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
    private val contentBindingRepository: ContentBindingRepository,
    private val structuredDiagnostics: StructuredDiagnosticRecorder,
) {

    suspend fun execute(
        canonicalTitleId: String,
        allowNetwork: Boolean = true,
    ): Manga? {
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
            if (!allowNetwork) continue

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

        if (!allowNetwork) {
            logcat {
                "TsuzukiCover sourceResolve title=$diagnosticId selected=local-only " +
                    "fallbackPresent=" + (fallback != null) +
                    " fallbackThumbnail=" + !fallback?.thumbnailUrl.isNullOrBlank()
            }
            return fallback
        }

        val bindingManga = resolveFromContentBindings(
            canonicalTitleId = canonicalTitleId,
            fallbackTitle = title.displayTitle,
            diagnosticId = diagnosticId,
        )
        if (!bindingManga?.thumbnailUrl.isNullOrBlank()) {
            return bindingManga
        }
        if (fallback == null) {
            fallback = bindingManga
        }

        logcat {
            "TsuzukiCover sourceResolve title=$diagnosticId selected=fallback " +
                "fallbackPresent=${fallback != null} fallbackThumbnail=${!fallback?.thumbnailUrl.isNullOrBlank()}"
        }
        return fallback
    }

    private suspend fun resolveFromContentBindings(
        canonicalTitleId: String,
        fallbackTitle: String,
        diagnosticId: String,
    ): Manga? {
        val repository = contentBindingRepository
        val trace = DiagnosticTrace.start(
            recorder = structuredDiagnostics,
            workflow = DiagnosticWorkflow.CONTENT_RESOLUTION,
            canonicalTitleId = canonicalTitleId,
            subsystem = DiagnosticSubsystem.CONTENT,
        )
        val bindings = try {
            repository.getByTitle(canonicalTitleId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logcat(LogPriority.WARN, error) {
                "TsuzukiCover sourceResolve title=$diagnosticId contentBindingsThrown=true"
            }
            return null
        }
            .filter {
                it.availability != ContentBindingAvailability.UNAVAILABLE &&
                    it.runtimePayload.isNotEmpty()
            }
            .sortedWith(
                compareByDescending<ContentBinding> { it.verifiedByUser }
                    .thenByDescending { it.matchConfidence }
                    .thenByDescending { it.updatedAt },
            )

        logcat {
            "TsuzukiCover sourceResolve title=$diagnosticId contentBindings=${bindings.size}"
        }
        trace.event(
            subsystem = DiagnosticSubsystem.CONTENT,
            name = DiagnosticEventName.CONTENT_BINDING_LOOKUP,
            stage = DiagnosticStage.LOOKUP,
            outcome = if (bindings.isEmpty()) DiagnosticOutcome.MISS else DiagnosticOutcome.HIT,
            attributes = mapOf(
                DiagnosticAttribute.BINDING_COUNT to DiagnosticAttributeValue.Number(bindings.size.toLong()),
            ),
        )

        var restoredCandidates = 0
        var fallback: Manga? = null
        for (binding in bindings) {
            val candidate = try {
                readingSourceGateway
                    .restoreMaterializedCandidate(binding.runtimePayload, fallbackTitle)
                    .getOrNull()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            } ?: continue
            restoredCandidates++

            val details = try {
                readingSourceGateway.getDetails(candidate).getOrElse { candidate }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                candidate
            }
            val enriched = Manga.create().copy(
                source = details.sourceId,
                url = details.sourceUrl,
                title = details.title.takeIf(String::isNotBlank) ?: fallbackTitle,
                thumbnailUrl = details.thumbnailUrl?.takeIf(String::isNotBlank),
                author = details.author,
                artist = details.artist,
                description = details.description,
                genre = details.genres,
                status = details.status,
                initialized = true,
            )
            val repaired = try {
                networkToLocalManga(enriched)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            } ?: continue

            if (fallback == null) fallback = repaired
            if (!repaired.thumbnailUrl.isNullOrBlank()) {
                logcat {
                    "TsuzukiCover sourceResolve title=$diagnosticId source=${repaired.source} " +
                        "selected=contentBinding"
                }
                return repaired
            }
        }
        if (bindings.isNotEmpty() && restoredCandidates == 0) {
            trace.event(
                subsystem = DiagnosticSubsystem.CONTENT,
                name = DiagnosticEventName.INVARIANT_VIOLATION,
                stage = DiagnosticStage.BINDING,
                outcome = DiagnosticOutcome.FAILED,
                severity = DiagnosticSeverity.ERROR,
                attributes = mapOf(
                    DiagnosticAttribute.INVARIANT_CODE to DiagnosticAttributeValue.Code(
                        DiagnosticInvariantCode.CONTENT_BINDING_EXISTS_BUT_NOT_CONSUMED,
                    ),
                    DiagnosticAttribute.BINDING_COUNT to DiagnosticAttributeValue.Number(bindings.size.toLong()),
                ),
            )
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
