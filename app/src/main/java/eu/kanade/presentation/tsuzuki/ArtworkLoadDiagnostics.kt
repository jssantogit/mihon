package eu.kanade.presentation.tsuzuki

import eu.kanade.presentation.manga.components.MangaCoverLoadEvent
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttribute
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticInvariantCode
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSeverity
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticTrace

fun DiagnosticTrace.recordArtworkLoad(event: MangaCoverLoadEvent) {
    when (event) {
        is MangaCoverLoadEvent.Attempt -> {
            if (event.candidateCount > 0 && !event.requestDataPresent) {
                event(
                    subsystem = DiagnosticSubsystem.IMAGE,
                    name = DiagnosticEventName.INVARIANT_VIOLATION,
                    stage = DiagnosticStage.RENDER,
                    outcome = DiagnosticOutcome.FAILED,
                    severity = DiagnosticSeverity.ERROR,
                    attributes = mapOf(
                        DiagnosticAttribute.INVARIANT_CODE to DiagnosticAttributeValue.Code(
                            DiagnosticInvariantCode.ARTWORK_LOST_AFTER_RESOLUTION,
                        ),
                        DiagnosticAttribute.CANDIDATE_COUNT to
                            DiagnosticAttributeValue.Number(event.candidateCount.toLong()),
                    ),
                )
            }
            if (event.candidateIndex == 0) {
                event(
                    subsystem = DiagnosticSubsystem.ARTWORK,
                    name = DiagnosticEventName.ARTWORK_CANDIDATES_BUILT,
                    stage = DiagnosticStage.RENDER,
                    outcome = if (event.candidateCount == 0) DiagnosticOutcome.EMPTY else DiagnosticOutcome.CANDIDATES,
                    attributes = mapOf(
                        DiagnosticAttribute.CANDIDATE_COUNT to
                            DiagnosticAttributeValue.Number(event.candidateCount.toLong()),
                        DiagnosticAttribute.REQUEST_DATA_PRESENT to
                            DiagnosticAttributeValue.Flag(event.requestDataPresent),
                    ),
                )
            }
            event(
            subsystem = DiagnosticSubsystem.IMAGE,
            name = DiagnosticEventName.ARTWORK_LOAD_ATTEMPT,
            stage = DiagnosticStage.IMAGE_LOAD,
            outcome = DiagnosticOutcome.STARTED,
            attributes = mapOf(
                DiagnosticAttribute.CANDIDATE_INDEX to
                    DiagnosticAttributeValue.Number(event.candidateIndex.toLong()),
                DiagnosticAttribute.CANDIDATE_COUNT to
                    DiagnosticAttributeValue.Number(event.candidateCount.toLong()),
                DiagnosticAttribute.REQUEST_DATA_PRESENT to
                    DiagnosticAttributeValue.Flag(event.requestDataPresent),
            ),
        )
        }
        is MangaCoverLoadEvent.Failed -> event(
            subsystem = DiagnosticSubsystem.IMAGE,
            name = DiagnosticEventName.ARTWORK_LOAD_COMPLETED,
            stage = DiagnosticStage.IMAGE_LOAD,
            outcome = if (event.willFallback) DiagnosticOutcome.PARTIAL else DiagnosticOutcome.FAILED,
            severity = if (event.willFallback) DiagnosticSeverity.WARN else DiagnosticSeverity.ERROR,
            attributes = mapOf(
                DiagnosticAttribute.CANDIDATE_INDEX to
                    DiagnosticAttributeValue.Number(event.candidateIndex.toLong()),
            ),
        )
        is MangaCoverLoadEvent.Succeeded -> event(
            subsystem = DiagnosticSubsystem.IMAGE,
            name = DiagnosticEventName.ARTWORK_LOAD_COMPLETED,
            stage = DiagnosticStage.IMAGE_LOAD,
            outcome = DiagnosticOutcome.SUCCEEDED,
            attributes = mapOf(
                DiagnosticAttribute.CANDIDATE_INDEX to
                    DiagnosticAttributeValue.Number(event.candidateIndex.toLong()),
            ),
        )
    }
}
