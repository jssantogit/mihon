package eu.kanade.presentation.tsuzuki

import eu.kanade.presentation.manga.components.MangaCoverLoadEvent
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttribute
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSeverity
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticTrace

fun DiagnosticTrace.recordArtworkLoad(event: MangaCoverLoadEvent) {
    when (event) {
        is MangaCoverLoadEvent.Attempt -> event(
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
