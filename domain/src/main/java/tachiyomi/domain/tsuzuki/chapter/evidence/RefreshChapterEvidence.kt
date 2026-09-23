package tachiyomi.domain.tsuzuki.chapter.evidence

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tachiyomi.domain.tsuzuki.addon.AddonRegistry
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticEvent
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticOutcome
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticReason
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticStage
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.chapter.diagnostics.NoOpChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.chapter.diagnostics.recordIfEnabled
import tachiyomi.domain.tsuzuki.content.cache.ContentOptionCache
import tachiyomi.domain.tsuzuki.content.interactor.ResolveContentBinding
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry

class RefreshChapterEvidence private constructor(
    private val registry: IntegrationRegistry,
    private val reconcileChapterEvidence: ReconcileChapterEvidence,
    private val addonRegistry: AddonRegistry?,
    private val resolveContentBinding: ResolveContentBinding?,
    private val contentOptionCache: ContentOptionCache?,
    private val diagnostics: ChapterInventoryDiagnostics,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit,
) {

    @Inject
    constructor(
        registry: IntegrationRegistry,
        reconcileChapterEvidence: ReconcileChapterEvidence,
        addonRegistry: AddonRegistry,
        resolveContentBinding: ResolveContentBinding,
        contentOptionCache: ContentOptionCache,
        diagnostics: ChapterInventoryDiagnostics,
    ) : this(
        registry = registry,
        reconcileChapterEvidence = reconcileChapterEvidence,
        addonRegistry = addonRegistry,
        resolveContentBinding = resolveContentBinding,
        contentOptionCache = contentOptionCache,
        diagnostics = diagnostics,
        constructorMarker = Unit,
    )

    constructor(
        registry: IntegrationRegistry,
        reconcileChapterEvidence: ReconcileChapterEvidence,
    ) : this(
        registry = registry,
        reconcileChapterEvidence = reconcileChapterEvidence,
        addonRegistry = null,
        resolveContentBinding = null,
        contentOptionCache = null,
        diagnostics = NoOpChapterInventoryDiagnostics,
        constructorMarker = Unit,
    )

    suspend fun execute(canonicalTitleId: String): Result<Unit> {
        return try {
            registry.awaitReady()
            // Integration metadata and Add-on inventories are independent until
            // reconciliation. Running both concurrently avoids serial network waits.
            val evidence = coroutineScope {
                val integrations = async { collectIntegrationEvidence(canonicalTitleId) }
                val addons = async { collectAddonEvidence(canonicalTitleId) }
                (integrations.await() + addons.await()).distinctBy(ChapterEvidence::id)
            }
            reconcileChapterEvidence.execute(canonicalTitleId, evidence)
            // Chapter mappings may have changed; never serve stale provider
            // options that were resolved against a previous evidence graph.
            contentOptionCache?.invalidateTitle(canonicalTitleId)
            Result.success(Unit)
        } catch (error: CancellationException) {
            if (error is kotlinx.coroutines.TimeoutCancellationException) {
                recordRefreshOutcome(
                    canonicalTitleId = canonicalTitleId,
                    outcome = ChapterInventoryDiagnosticOutcome.TIMEOUT,
                    reason = ChapterInventoryDiagnosticReason.BINDING_UNAVAILABLE,
                )
            }
            throw error
        } catch (error: Throwable) {
            recordRefreshOutcome(
                canonicalTitleId = canonicalTitleId,
                outcome = error.toDiagnosticOutcome(),
                reason = ChapterInventoryDiagnosticReason.BINDING_UNAVAILABLE,
            )
            Result.failure(error)
        }
    }

    private suspend fun collectIntegrationEvidence(
        canonicalTitleId: String,
    ): List<ChapterEvidence> = coroutineScope {
        val gate = Semaphore(MAX_CONCURRENT_EVIDENCE_PROVIDERS)
        registry.chapterEvidenceProviders()
            .map { provider ->
                async {
                    gate.withPermit {
                        try {
                            provider.evidenceFor(canonicalTitleId)
                                .fold(
                                    onSuccess = { observations ->
                                        observations.takeIf {
                                            it.all { observation ->
                                                observation.canonicalTitleId == canonicalTitleId
                                            }
                                        }.orEmpty()
                                    },
                                    onFailure = { error ->
                                        if (error is CancellationException) throw error
                                        recordRefreshOutcome(
                                            canonicalTitleId = canonicalTitleId,
                                            outcome = error.toDiagnosticOutcome(),
                                            received = 0,
                                        )
                                        emptyList()
                                    },
                                )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            recordRefreshOutcome(
                                canonicalTitleId = canonicalTitleId,
                                outcome = error.toDiagnosticOutcome(),
                            )
                            emptyList()
                        }
                    }
                }
            }
            .awaitAll()
            .flatten()
    }

    private suspend fun collectAddonEvidence(
        canonicalTitleId: String,
    ): List<ChapterEvidence> {
        val addonRegistry = addonRegistry ?: return emptyList()
        val resolver = resolveContentBinding ?: return emptyList()
        try {
            addonRegistry.awaitReady()
        } catch (error: CancellationException) {
            if (error is kotlinx.coroutines.TimeoutCancellationException) {
                recordRefreshOutcome(
                    canonicalTitleId = canonicalTitleId,
                    outcome = ChapterInventoryDiagnosticOutcome.TIMEOUT,
                    reason = ChapterInventoryDiagnosticReason.BINDING_UNAVAILABLE,
                )
            }
            throw error
        } catch (error: Throwable) {
            recordRefreshOutcome(
                canonicalTitleId = canonicalTitleId,
                outcome = error.toDiagnosticOutcome(),
                reason = ChapterInventoryDiagnosticReason.BINDING_UNAVAILABLE,
            )
            throw error
        }

        return coroutineScope {
            val gate = Semaphore(MAX_CONCURRENT_EVIDENCE_PROVIDERS)
            addonRegistry.chapterProbeProviders()
                .map { provider ->
                    async {
                        gate.withPermit {
                            try {
                                val bindings = resolver
                                    .executeAll(canonicalTitleId, provider.addonId)
                                    .getOrElse { error ->
                                        if (error is CancellationException) throw error
                                        recordRefreshOutcome(
                                            canonicalTitleId = canonicalTitleId,
                                            addonId = provider.addonId.value,
                                            outcome = error.toDiagnosticOutcome(),
                                            reason = ChapterInventoryDiagnosticReason.BINDING_UNAVAILABLE,
                                        )
                                        return@withPermit emptyList()
                                    }
                                if (bindings.isEmpty()) {
                                    recordRefreshOutcome(
                                        canonicalTitleId = canonicalTitleId,
                                        addonId = provider.addonId.value,
                                        outcome = ChapterInventoryDiagnosticOutcome.NO_BINDING,
                                        reason = ChapterInventoryDiagnosticReason.NO_BINDING,
                                    )
                                    return@withPermit emptyList()
                                }

                                provider.probe(canonicalTitleId)
                                    .fold(
                                        onSuccess = { observations ->
                                            val accepted = observations.takeIf {
                                                it.all { observation ->
                                                    observation.canonicalTitleId == canonicalTitleId
                                                }
                                            }.orEmpty()
                                            recordRefreshOutcome(
                                                canonicalTitleId = canonicalTitleId,
                                                addonId = provider.addonId.value,
                                                outcome = if (accepted.isEmpty()) {
                                                    ChapterInventoryDiagnosticOutcome.EMPTY
                                                } else {
                                                    ChapterInventoryDiagnosticOutcome.SUCCESS
                                                },
                                                received = observations.size,
                                                accepted = accepted.size,
                                                discarded = observations.size - accepted.size,
                                            )
                                            accepted
                                        },
                                        onFailure = { error ->
                                            if (error is CancellationException) throw error
                                            recordRefreshOutcome(
                                                canonicalTitleId = canonicalTitleId,
                                                addonId = provider.addonId.value,
                                                outcome = error.toDiagnosticOutcome(),
                                            )
                                            emptyList()
                                        },
                                    )
                            } catch (error: CancellationException) {
                                if (error is kotlinx.coroutines.TimeoutCancellationException) {
                                    recordRefreshOutcome(
                                        canonicalTitleId = canonicalTitleId,
                                        addonId = provider.addonId.value,
                                        outcome = ChapterInventoryDiagnosticOutcome.TIMEOUT,
                                    )
                                }
                                throw error
                            } catch (error: Throwable) {
                                recordRefreshOutcome(
                                    canonicalTitleId = canonicalTitleId,
                                    addonId = provider.addonId.value,
                                    outcome = error.toDiagnosticOutcome(),
                                )
                                emptyList()
                            }
                        }
                    }
                }
                .awaitAll()
                .flatten()
        }
    }

    /**
     * Entry point for non-Integration producers, such as Add-ons, to submit
     * provisional observations without invoking Integration providers.
     */
    suspend fun submit(
        canonicalTitleId: String,
        evidence: List<ChapterEvidence>,
    ): Result<Unit> {
        return try {
            reconcileChapterEvidence.execute(canonicalTitleId, evidence)
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun recordRefreshOutcome(
        canonicalTitleId: String,
        addonId: String? = null,
        outcome: ChapterInventoryDiagnosticOutcome,
        reason: ChapterInventoryDiagnosticReason? = null,
        received: Int = 0,
        accepted: Int = 0,
        discarded: Int = 0,
    ) {
        diagnostics.recordIfEnabled(
            canonicalTitleId,
            ChapterInventoryDiagnosticEvent(
                stage = ChapterInventoryDiagnosticStage.PROBE,
                outcome = outcome,
                addonId = addonId,
                received = received,
                accepted = accepted,
                provisional = 0,
                discarded = discarded,
                reasons = reason?.let { mapOf(it to 1) }.orEmpty(),
            ),
        )
    }

    private fun Throwable.toDiagnosticOutcome(): ChapterInventoryDiagnosticOutcome {
        val causes = generateSequence(this) { it.cause }.take(MAX_CAUSES).toList()
        return when {
            causes.any {
                it is java.net.SocketTimeoutException || it is kotlinx.coroutines.TimeoutCancellationException
            } -> ChapterInventoryDiagnosticOutcome.TIMEOUT
            causes.any { it is java.io.IOException } -> ChapterInventoryDiagnosticOutcome.NETWORK_ERROR
            else -> ChapterInventoryDiagnosticOutcome.EXTENSION_ERROR
        }
    }

    private companion object {
        const val MAX_CONCURRENT_EVIDENCE_PROVIDERS = 4
        const val MAX_CAUSES = 5
    }
}
