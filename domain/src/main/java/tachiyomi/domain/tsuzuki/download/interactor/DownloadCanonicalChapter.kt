package tachiyomi.domain.tsuzuki.download.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.content.ContentOption
import tachiyomi.domain.tsuzuki.content.interactor.ResolveChapterContent
import tachiyomi.domain.tsuzuki.content.repository.ContentPreferenceRepository
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttribute
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSeverity
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticTrace
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticWorkflow
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.download.model.CanonicalDownloadPreparation
import tachiyomi.domain.tsuzuki.download.repository.CanonicalDownloadRepository
import tachiyomi.domain.tsuzuki.download.service.CanonicalDownloadGateway
import kotlin.time.TimeMark
import kotlin.time.TimeSource

@Inject
class DownloadCanonicalChapter(
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val contentPreferenceRepository: ContentPreferenceRepository,
    private val resolveChapterContent: ResolveChapterContent,
    private val canonicalDownloadRepository: CanonicalDownloadRepository,
    private val canonicalDownloadGateway: CanonicalDownloadGateway,
    private val structuredDiagnostics: StructuredDiagnosticRecorder,
) {

    suspend fun execute(
        canonicalChapterId: String,
        selectedOption: ContentOption? = null,
    ): CanonicalDownloadPreparation {
        val trace = DiagnosticTrace.start(
            recorder = structuredDiagnostics,
            workflow = DiagnosticWorkflow.DOWNLOAD_CHAPTER,
            subsystem = DiagnosticSubsystem.DOWNLOAD,
        )
        val started = TimeSource.Monotonic.markNow()
        trace.event(
            subsystem = DiagnosticSubsystem.DOWNLOAD,
            name = DiagnosticEventName.DOWNLOAD_STARTED,
            stage = DiagnosticStage.DOWNLOAD,
            outcome = DiagnosticOutcome.STARTED,
        )

        val result = try {
            val cached = canonicalDownloadRepository.get(canonicalChapterId)
            trace.event(
                subsystem = DiagnosticSubsystem.DOWNLOAD,
                name = DiagnosticEventName.CACHE_LOOKUP,
                stage = DiagnosticStage.LOOKUP,
                outcome = if (cached == null) DiagnosticOutcome.MISS else DiagnosticOutcome.HIT,
            )
            if (cached != null) {
                return complete(
                    trace,
                    started,
                    CanonicalDownloadPreparation.Complete(
                        canonicalChapterId = canonicalChapterId,
                        artifact = cached,
                        reused = true,
                    ),
                )
            }

            val chapter = canonicalChapterRepository.getById(canonicalChapterId)
                ?: return complete(
                    trace,
                    started,
                    CanonicalDownloadPreparation.Unavailable(canonicalChapterId),
                )

            if (selectedOption != null) {
                require(selectedOption.canonicalChapterId == canonicalChapterId) {
                    "Selected content option does not belong to canonical chapter"
                }
                return complete(trace, started, acquire(canonicalChapterId, selectedOption))
            }

            val options = resolveChapterContent.resolveOptions(
                canonicalTitleId = chapter.canonicalTitleId,
                canonicalChapterId = canonicalChapterId,
            )
            if (options.isEmpty()) {
                return complete(
                    trace,
                    started,
                    CanonicalDownloadPreparation.Unavailable(canonicalChapterId),
                )
            }

            val preferredAddonId = contentPreferenceRepository
                .get(chapter.canonicalTitleId)
                ?.preferredAddonId
            val preferred = preferredAddonId?.let { addonId ->
                options.firstOrNull { it.addonId == addonId }
            }

            if (preferred == null) {
                return complete(
                    trace,
                    started,
                    CanonicalDownloadPreparation.SelectionRequired(
                        canonicalTitleId = chapter.canonicalTitleId,
                        canonicalChapterId = canonicalChapterId,
                        options = options,
                        preferredAddonId = preferredAddonId,
                    ),
                )
            }

            acquire(canonicalChapterId, preferred)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            CanonicalDownloadPreparation.Failed(canonicalChapterId, error)
        }

        return complete(trace, started, result)
    }

    private fun complete(
        trace: DiagnosticTrace,
        started: TimeMark,
        result: CanonicalDownloadPreparation,
    ): CanonicalDownloadPreparation {
        val duration = started.elapsedNow().inWholeMilliseconds.coerceAtLeast(0)
        when (result) {
            is CanonicalDownloadPreparation.Complete -> {
                trace.event(
                    subsystem = DiagnosticSubsystem.DOWNLOAD,
                    name = DiagnosticEventName.DOWNLOAD_COMPLETED,
                    stage = DiagnosticStage.COMPLETE,
                    outcome = DiagnosticOutcome.SUCCEEDED,
                    durationMillis = duration,
                    attributes = mapOf(
                        DiagnosticAttribute.MAPPING_REUSED to DiagnosticAttributeValue.Flag(result.reused),
                    ),
                )
            }
            is CanonicalDownloadPreparation.SelectionRequired -> trace.event(
                subsystem = DiagnosticSubsystem.DOWNLOAD,
                name = DiagnosticEventName.DOWNLOAD_PREPARED,
                stage = DiagnosticStage.DOWNLOAD,
                outcome = DiagnosticOutcome.NEEDS_CONFIRMATION,
                durationMillis = duration,
                attributes = mapOf(
                    DiagnosticAttribute.CANDIDATE_COUNT to
                        DiagnosticAttributeValue.Number(result.options.size.toLong()),
                ),
            )
            is CanonicalDownloadPreparation.Unavailable -> trace.event(
                subsystem = DiagnosticSubsystem.DOWNLOAD,
                name = DiagnosticEventName.DOWNLOAD_COMPLETED,
                stage = DiagnosticStage.COMPLETE,
                outcome = DiagnosticOutcome.EMPTY,
                durationMillis = duration,
            )
            is CanonicalDownloadPreparation.Failed -> trace.event(
                subsystem = DiagnosticSubsystem.DOWNLOAD,
                name = DiagnosticEventName.DOWNLOAD_COMPLETED,
                stage = DiagnosticStage.COMPLETE,
                outcome = DiagnosticOutcome.FAILED,
                severity = DiagnosticSeverity.ERROR,
                durationMillis = duration,
            )
        }
        return result
    }

    private suspend fun acquire(
        canonicalChapterId: String,
        option: ContentOption,
    ): CanonicalDownloadPreparation {
        return canonicalDownloadGateway.acquire(option).fold(
            onSuccess = { artifact ->
                require(artifact.canonicalChapterId == canonicalChapterId) {
                    "Acquired artifact does not belong to canonical chapter"
                }
                canonicalDownloadRepository.upsert(artifact)
                CanonicalDownloadPreparation.Complete(
                    canonicalChapterId = canonicalChapterId,
                    artifact = artifact,
                    reused = false,
                )
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                CanonicalDownloadPreparation.Failed(canonicalChapterId, error)
            },
        )
    }

    suspend operator fun invoke(
        canonicalChapterId: String,
    ): CanonicalDownloadPreparation = execute(canonicalChapterId)
}
