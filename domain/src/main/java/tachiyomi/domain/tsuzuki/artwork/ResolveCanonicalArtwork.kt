package tachiyomi.domain.tsuzuki.artwork

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.artwork.model.ResolvedCanonicalArtwork
import tachiyomi.domain.tsuzuki.artwork.model.TitleArtworkObservation
import tachiyomi.domain.tsuzuki.artwork.repository.TitleArtworkRepository
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttribute
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticTrace
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticWorkflow
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder
import kotlin.time.TimeSource

@Inject
class ResolveCanonicalArtwork(
    private val repository: TitleArtworkRepository,
    private val diagnosticRecorder: StructuredDiagnosticRecorder,
) {

    suspend fun execute(canonicalTitleId: String): ResolvedCanonicalArtwork? {
        val trace = DiagnosticTrace.start(
            recorder = diagnosticRecorder,
            workflow = DiagnosticWorkflow.ARTWORK_RESOLUTION,
            canonicalTitleId = canonicalTitleId,
            subsystem = DiagnosticSubsystem.ARTWORK,
        )
        val started = TimeSource.Monotonic.markNow()
        trace.event(
            subsystem = DiagnosticSubsystem.ARTWORK,
            name = DiagnosticEventName.ARTWORK_RESOLVE_STARTED,
            stage = DiagnosticStage.RESOLVE,
            outcome = DiagnosticOutcome.STARTED,
        )
        val observations = repository.getByTitle(canonicalTitleId)
        trace.event(
            subsystem = DiagnosticSubsystem.ARTWORK,
            name = DiagnosticEventName.ARTWORK_OBSERVATIONS_READ,
            stage = DiagnosticStage.READ,
            outcome = if (observations.isEmpty()) DiagnosticOutcome.EMPTY else DiagnosticOutcome.SUCCEEDED,
            attributes = mapOf(
                DiagnosticAttribute.OBSERVATION_COUNT to DiagnosticAttributeValue.Number(observations.size.toLong()),
            ),
        )

        val resolved = resolveCanonicalArtwork(observations)
        resolved?.coverProvider?.let { provider ->
            trace.event(
                subsystem = DiagnosticSubsystem.ARTWORK,
                name = DiagnosticEventName.ARTWORK_PROVIDER_SELECTED,
                stage = DiagnosticStage.RESOLVE,
                outcome = DiagnosticOutcome.SUCCEEDED,
                attributes = mapOf(
                    DiagnosticAttribute.PROVIDER_ID to DiagnosticAttributeValue.Text(provider),
                    DiagnosticAttribute.COVER_PRESENT to DiagnosticAttributeValue.Flag(!resolved.coverUrl.isNullOrBlank()),
                ),
            )
        }
        val duration = started.elapsedNow().inWholeMilliseconds.coerceAtLeast(0)
        trace.event(
            subsystem = DiagnosticSubsystem.ARTWORK,
            name = DiagnosticEventName.WORKFLOW_COMPLETED,
            stage = DiagnosticStage.COMPLETE,
            outcome = if (resolved == null) DiagnosticOutcome.EMPTY else DiagnosticOutcome.SUCCEEDED,
            durationMillis = duration,
            attributes = mapOf(
                DiagnosticAttribute.CANONICAL_ARTWORK_PRESENT to
                    DiagnosticAttributeValue.Flag(!resolved?.coverUrl.isNullOrBlank()),
            ),
        )
        return resolved
    }
}

fun resolveCanonicalArtwork(
    observations: List<TitleArtworkObservation>,
): ResolvedCanonicalArtwork? {
    val ordered = observations.sortedWith(
        compareBy<TitleArtworkObservation>(
            { observation ->
                ARTWORK_PROVIDER_PRECEDENCE.indexOf(observation.provider)
                    .takeIf { it >= 0 }
                    ?: Int.MAX_VALUE
            },
            TitleArtworkObservation::provider,
            { -it.updatedAt },
        ),
    )
    val cover = ordered.firstOrNull { !it.coverUrl.isNullOrBlank() }
    val banner = ordered.firstOrNull { !it.bannerUrl.isNullOrBlank() }
    if (cover == null && banner == null) return null

    return ResolvedCanonicalArtwork(
        coverUrl = cover?.coverUrl,
        coverProvider = cover?.provider,
        bannerUrl = banner?.bannerUrl,
        bannerProvider = banner?.provider,
    )
}

private val ARTWORK_PROVIDER_PRECEDENCE = listOf(
    "kitsu",
    "mal",
    "mangaupdates",
    "bangumi",
)
