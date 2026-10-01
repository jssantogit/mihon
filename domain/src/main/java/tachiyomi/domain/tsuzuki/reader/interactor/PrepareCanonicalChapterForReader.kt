package tachiyomi.domain.tsuzuki.reader.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.content.ContentOption
import tachiyomi.domain.tsuzuki.content.interactor.ResolveChapterContent
import tachiyomi.domain.tsuzuki.content.model.ContentResolution
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
import tachiyomi.domain.tsuzuki.download.repository.CanonicalDownloadRepository
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreparation
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import tachiyomi.domain.tsuzuki.reader.service.ChapterContentPreparer
import kotlin.time.TimeMark
import kotlin.time.TimeSource

@Inject
class PrepareCanonicalChapterForReader(
    private val resolveChapterContent: ResolveChapterContent,
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val canonicalReadingRepository: CanonicalReadingRepository,
    private val canonicalDownloadRepository: CanonicalDownloadRepository,
    private val chapterContentPreparer: ChapterContentPreparer,
    private val structuredDiagnostics: StructuredDiagnosticRecorder,
) {

    suspend fun execute(
        canonicalChapterId: String,
        selectedOption: ContentOption? = null,
    ): CanonicalReaderPreparation {
        val trace = DiagnosticTrace.start(
            recorder = structuredDiagnostics,
            workflow = DiagnosticWorkflow.READER_OPEN,
            subsystem = DiagnosticSubsystem.READER,
        )
        val started = TimeSource.Monotonic.markNow()
        trace.event(
            subsystem = DiagnosticSubsystem.READER,
            name = DiagnosticEventName.READER_OPEN_STARTED,
            stage = DiagnosticStage.READER,
            outcome = DiagnosticOutcome.STARTED,
        )

        val result = try {
            val chapter = canonicalChapterRepository.getById(canonicalChapterId)
                ?: return complete(
                    trace,
                    started,
                    CanonicalReaderPreparation.Unavailable(canonicalChapterId),
                )

            if (selectedOption == null) {
                val artifact = canonicalDownloadRepository.get(canonicalChapterId)
                trace.event(
                    subsystem = DiagnosticSubsystem.READER,
                    name = DiagnosticEventName.CACHE_LOOKUP,
                    stage = DiagnosticStage.LOOKUP,
                    outcome = if (artifact == null) DiagnosticOutcome.MISS else DiagnosticOutcome.HIT,
                )
                if (artifact != null) {
                    return complete(
                        trace,
                        started,
                        CanonicalReaderPreparation.Ready(
                            canonicalChapterId = canonicalChapterId,
                            target = tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent.CanonicalDownload(
                                uri = artifact.localUri,
                                format = artifact.format,
                            ),
                            usedFallback = false,
                        ),
                    )
                }
            }

            val resolution = if (selectedOption != null) {
                require(selectedOption.canonicalChapterId == canonicalChapterId) {
                    "Selected content option does not belong to canonical chapter"
                }
                ContentResolution.Direct(
                    option = selectedOption,
                    usedFallback = false,
                )
            } else {
                resolveChapterContent.execute(
                    canonicalTitleId = chapter.canonicalTitleId,
                    canonicalChapterId = canonicalChapterId,
                )
            }

            when (resolution) {
                is ContentResolution.Direct -> prepare(
                    canonicalChapterId = canonicalChapterId,
                    option = resolution.option,
                    usedFallback = resolution.usedFallback,
                )

                is ContentResolution.NeedsSelection -> CanonicalReaderPreparation.SelectionRequired(
                    canonicalTitleId = chapter.canonicalTitleId,
                    canonicalChapterId = canonicalChapterId,
                    options = resolution.options,
                    preferredAddonId = resolution.preferredAddonId,
                    preferredUnavailable = resolution.preferredUnavailable,
                )

                ContentResolution.Unavailable -> CanonicalReaderPreparation.Unavailable(canonicalChapterId)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            CanonicalReaderPreparation.Failed(canonicalChapterId, error)
        }

        return complete(trace, started, result)
    }

    private fun complete(
        trace: DiagnosticTrace,
        started: TimeMark,
        result: CanonicalReaderPreparation,
    ): CanonicalReaderPreparation {
        val duration = started.elapsedNow().inWholeMilliseconds.coerceAtLeast(0)
        when (result) {
            is CanonicalReaderPreparation.Ready -> {
                result.selectedOption?.let { option ->
                    trace.event(
                        subsystem = DiagnosticSubsystem.READER,
                        name = DiagnosticEventName.READER_SOURCE_SELECTED,
                        stage = DiagnosticStage.READER,
                        outcome = DiagnosticOutcome.SUCCEEDED,
                        attributes = mapOf(
                            DiagnosticAttribute.ADDON_ID to DiagnosticAttributeValue.Text(option.addonId.value),
                        ),
                    )
                }
                trace.event(
                    subsystem = DiagnosticSubsystem.READER,
                    name = DiagnosticEventName.READER_OPEN_COMPLETED,
                    stage = DiagnosticStage.COMPLETE,
                    outcome = DiagnosticOutcome.READY,
                    durationMillis = duration,
                )
            }
            is CanonicalReaderPreparation.SelectionRequired -> trace.event(
                subsystem = DiagnosticSubsystem.READER,
                name = DiagnosticEventName.READER_OPEN_COMPLETED,
                stage = DiagnosticStage.COMPLETE,
                outcome = DiagnosticOutcome.NEEDS_CONFIRMATION,
                durationMillis = duration,
                attributes = mapOf(
                    DiagnosticAttribute.CANDIDATE_COUNT to
                        DiagnosticAttributeValue.Number(result.options.size.toLong()),
                ),
            )
            is CanonicalReaderPreparation.Unavailable -> trace.event(
                subsystem = DiagnosticSubsystem.READER,
                name = DiagnosticEventName.READER_OPEN_COMPLETED,
                stage = DiagnosticStage.COMPLETE,
                outcome = DiagnosticOutcome.EMPTY,
                durationMillis = duration,
            )
            is CanonicalReaderPreparation.Failed -> trace.event(
                subsystem = DiagnosticSubsystem.READER,
                name = DiagnosticEventName.READER_OPEN_COMPLETED,
                stage = DiagnosticStage.COMPLETE,
                outcome = DiagnosticOutcome.FAILED,
                severity = DiagnosticSeverity.ERROR,
                durationMillis = duration,
            )
        }
        return result
    }

    private suspend fun prepare(
        canonicalChapterId: String,
        option: ContentOption,
        usedFallback: Boolean,
    ): CanonicalReaderPreparation {
        if (!resolveChapterContent.isOptionEnabled(option)) {
            return CanonicalReaderPreparation.Unavailable(canonicalChapterId)
        }
        val progress = canonicalReadingRepository.getProgress(canonicalChapterId)
        return chapterContentPreparer.prepare(option, progress).fold(
            onSuccess = { target ->
                CanonicalReaderPreparation.Ready(
                    canonicalChapterId = canonicalChapterId,
                    target = target,
                    usedFallback = usedFallback,
                    selectedOption = option,
                )
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                CanonicalReaderPreparation.Failed(canonicalChapterId, error)
            },
        )
    }

    suspend operator fun invoke(
        canonicalChapterId: String,
    ): CanonicalReaderPreparation = execute(canonicalChapterId)
}
