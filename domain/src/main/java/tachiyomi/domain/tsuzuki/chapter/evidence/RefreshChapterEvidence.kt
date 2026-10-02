package tachiyomi.domain.tsuzuki.chapter.evidence

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import tachiyomi.domain.tsuzuki.addon.AddonRegistry
import tachiyomi.domain.tsuzuki.addon.ChapterProbeRefresh
import tachiyomi.domain.tsuzuki.addon.RefreshAwareChapterProbeProvider
import tachiyomi.domain.tsuzuki.addon.TargetedChapterProbeProvider
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticEvent
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticFailures
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticOutcome
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticReason
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticStage
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.chapter.diagnostics.NoOpChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.chapter.diagnostics.recordIfEnabled
import tachiyomi.domain.tsuzuki.chapter.refresh.ChapterRefreshConfigurationFingerprint
import tachiyomi.domain.tsuzuki.chapter.refresh.ChapterRefreshSnapshot
import tachiyomi.domain.tsuzuki.chapter.refresh.ChapterRefreshSnapshotRepository
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentBindingAvailability
import tachiyomi.domain.tsuzuki.content.cache.ContentOptionCache
import tachiyomi.domain.tsuzuki.content.cache.InFlightContentResolution
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingConfirmationRequiredException
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingNotFoundException
import tachiyomi.domain.tsuzuki.content.interactor.DiscoverReadableTitle
import tachiyomi.domain.tsuzuki.content.interactor.ResolveContentBinding
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttribute
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSeverity
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticTrace
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticWorkflow
import tachiyomi.domain.tsuzuki.diagnostics.NoOpStructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import kotlin.time.Clock
import kotlin.time.TimeSource

class RefreshChapterEvidence private constructor(
    private val registry: IntegrationRegistry,
    private val reconcileChapterEvidence: ReconcileChapterEvidence,
    private val addonRegistry: AddonRegistry?,
    private val resolveContentBinding: ResolveContentBinding?,
    private val contentOptionCache: ContentOptionCache?,
    private val inFlightContentResolution: InFlightContentResolution?,
    private val diagnostics: ChapterInventoryDiagnostics,
    private val discoverReadableTitle: DiscoverReadableTitle?,
    private val structuredDiagnostics: StructuredDiagnosticRecorder,
    private val refreshSnapshots: ChapterRefreshSnapshotRepository?,
    private val clock: () -> Long,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit,
) {

    @Inject
    constructor(
        registry: IntegrationRegistry,
        reconcileChapterEvidence: ReconcileChapterEvidence,
        addonRegistry: AddonRegistry,
        resolveContentBinding: ResolveContentBinding,
        contentOptionCache: ContentOptionCache,
        inFlightContentResolution: InFlightContentResolution,
        diagnostics: ChapterInventoryDiagnostics,
        discoverReadableTitle: DiscoverReadableTitle,
        structuredDiagnostics: StructuredDiagnosticRecorder,
        refreshSnapshots: ChapterRefreshSnapshotRepository,
    ) : this(
        registry = registry,
        reconcileChapterEvidence = reconcileChapterEvidence,
        addonRegistry = addonRegistry,
        resolveContentBinding = resolveContentBinding,
        contentOptionCache = contentOptionCache,
        inFlightContentResolution = inFlightContentResolution,
        diagnostics = diagnostics,
        discoverReadableTitle = discoverReadableTitle,
        structuredDiagnostics = structuredDiagnostics,
        refreshSnapshots = refreshSnapshots,
        clock = { Clock.System.now().toEpochMilliseconds() },
        constructorMarker = Unit,
    )

    constructor(
        registry: IntegrationRegistry,
        reconcileChapterEvidence: ReconcileChapterEvidence,
        addonRegistry: AddonRegistry,
        resolveContentBinding: ResolveContentBinding,
        contentOptionCache: ContentOptionCache,
        diagnostics: ChapterInventoryDiagnostics,
        discoverReadableTitle: DiscoverReadableTitle? = null,
    ) : this(
        registry = registry,
        reconcileChapterEvidence = reconcileChapterEvidence,
        addonRegistry = addonRegistry,
        resolveContentBinding = resolveContentBinding,
        contentOptionCache = contentOptionCache,
        inFlightContentResolution = null,
        diagnostics = diagnostics,
        discoverReadableTitle = discoverReadableTitle,
        structuredDiagnostics = NoOpStructuredDiagnosticRecorder,
        refreshSnapshots = null,
        clock = { Clock.System.now().toEpochMilliseconds() },
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
        inFlightContentResolution = null,
        diagnostics = NoOpChapterInventoryDiagnostics,
        discoverReadableTitle = null,
        structuredDiagnostics = NoOpStructuredDiagnosticRecorder,
        refreshSnapshots = null,
        clock = { Clock.System.now().toEpochMilliseconds() },
        constructorMarker = Unit,
    )

    constructor(
        registry: IntegrationRegistry,
        reconcileChapterEvidence: ReconcileChapterEvidence,
        addonRegistry: AddonRegistry,
        resolveContentBinding: ResolveContentBinding,
        contentOptionCache: ContentOptionCache,
        inFlightContentResolution: InFlightContentResolution,
        diagnostics: ChapterInventoryDiagnostics,
        discoverReadableTitle: DiscoverReadableTitle,
        structuredDiagnostics: StructuredDiagnosticRecorder,
    ) : this(
        registry = registry,
        reconcileChapterEvidence = reconcileChapterEvidence,
        addonRegistry = addonRegistry,
        resolveContentBinding = resolveContentBinding,
        contentOptionCache = contentOptionCache,
        inFlightContentResolution = inFlightContentResolution,
        diagnostics = diagnostics,
        discoverReadableTitle = discoverReadableTitle,
        structuredDiagnostics = structuredDiagnostics,
        refreshSnapshots = null,
        clock = { Clock.System.now().toEpochMilliseconds() },
        constructorMarker = Unit,
    )

    constructor(
        registry: IntegrationRegistry,
        reconcileChapterEvidence: ReconcileChapterEvidence,
        refreshSnapshots: ChapterRefreshSnapshotRepository,
        clock: () -> Long,
    ) : this(
        registry = registry,
        reconcileChapterEvidence = reconcileChapterEvidence,
        addonRegistry = null,
        resolveContentBinding = null,
        contentOptionCache = null,
        inFlightContentResolution = null,
        diagnostics = NoOpChapterInventoryDiagnostics,
        discoverReadableTitle = null,
        structuredDiagnostics = NoOpStructuredDiagnosticRecorder,
        refreshSnapshots = refreshSnapshots,
        clock = clock,
        constructorMarker = Unit,
    )

    suspend fun execute(
        canonicalTitleId: String,
        forceRefresh: Boolean = false,
    ): Result<Unit> = executeInternal(
        canonicalTitleId = canonicalTitleId,
        forceRefresh = forceRefresh,
        onStageReconciled = {},
    )

    suspend fun executeProgressively(
        canonicalTitleId: String,
        forceRefresh: Boolean = false,
        onStageReconciled: suspend () -> Unit,
    ): Result<Unit> = executeInternal(
        canonicalTitleId = canonicalTitleId,
        forceRefresh = forceRefresh,
        onStageReconciled = onStageReconciled,
    )

    private suspend fun executeInternal(
        canonicalTitleId: String,
        forceRefresh: Boolean,
        onStageReconciled: suspend () -> Unit,
    ): Result<Unit> {
        val trace = DiagnosticTrace.start(
            recorder = structuredDiagnostics,
            workflow = DiagnosticWorkflow.CHAPTER_REFRESH,
            canonicalTitleId = canonicalTitleId,
            subsystem = DiagnosticSubsystem.CHAPTER,
        )
        val started = TimeSource.Monotonic.markNow()
        trace.event(
            subsystem = DiagnosticSubsystem.CHAPTER,
            name = DiagnosticEventName.CHAPTER_REFRESH_STARTED,
            stage = DiagnosticStage.RECONCILE,
            outcome = DiagnosticOutcome.STARTED,
        )
        return try {
            registry.awaitReady()
            addonRegistry?.awaitReady()
            val configurationFingerprint = if (refreshSnapshots == null) {
                ""
            } else {
                refreshConfigurationFingerprint()
            }
            if (!forceRefresh && isFresh(canonicalTitleId, configurationFingerprint)) {
                trace.event(
                    subsystem = DiagnosticSubsystem.CHAPTER,
                    name = DiagnosticEventName.CHAPTER_REFRESH_COMPLETED,
                    stage = DiagnosticStage.COMPLETE,
                    outcome = DiagnosticOutcome.SUCCEEDED,
                    durationMillis = started.elapsedNow().inWholeMilliseconds.coerceAtLeast(0),
                )
                return Result.success(Unit)
            }

            val stagedEvidence = linkedMapOf<String, ChapterEvidence>()
            val stageMutex = Mutex()

            suspend fun reconcileStage(
                observations: List<ChapterEvidence>,
                pendingSnapshots: List<ChapterRefreshSnapshot> = emptyList(),
            ) {
                stageMutex.lock()
                try {
                    val delta = observations.filter { observation ->
                        stagedEvidence[observation.id] != observation
                    }
                    if (delta.isNotEmpty()) {
                        reconcileChapterEvidence.execute(canonicalTitleId, delta)
                        delta.forEach { observation -> stagedEvidence[observation.id] = observation }
                    }
                    pendingSnapshots
                        .associateBy(ChapterRefreshSnapshot::scopeKey)
                        .values
                        .forEach { snapshot -> refreshSnapshots?.upsertIfNewer(snapshot) }

                    if (delta.isNotEmpty()) {
                        invalidateContentOptionsAfterChapterRefresh(
                            invalidateInFlight = {
                                inFlightContentResolution?.invalidateTitle(canonicalTitleId)
                            },
                            invalidateCache = { contentOptionCache?.invalidateTitle(canonicalTitleId) },
                        )
                        try {
                            onStageReconciled()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Throwable) {
                            // Progressive publication is observational; canonical refresh stays authoritative.
                        }
                    }
                } finally {
                    stageMutex.unlock()
                }
            }

            val collections = coroutineScope {
                val initialAddons = async {
                    collectAddonEvidence(canonicalTitleId) { batch ->
                        reconcileStage(batch.evidence, batch.pendingSnapshots)
                    }
                }
                val integrationEvidence = collectIntegrationEvidence(canonicalTitleId) { batch ->
                    reconcileStage(batch.evidence)
                }
                var addonEvidence = initialAddons.await()
                val knownBindingIds = addonEvidence.bindingIds.toMutableSet()

                if (addonEvidence.observedChapterCount == 0) {
                    val discovered = try {
                        discoverReadableTitle?.execute(canonicalTitleId)?.getOrNull().orEmpty()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        emptyList()
                    }
                    val newBindings = discovered.filter { it.id !in knownBindingIds }
                    if (newBindings.isNotEmpty()) {
                        knownBindingIds += newBindings.map(ContentBinding::id)
                        addonEvidence = addonEvidence.merge(
                            collectAddonEvidence(canonicalTitleId) { batch ->
                                reconcileStage(batch.evidence, batch.pendingSnapshots)
                            },
                        )
                    }
                }

                var broadenAttempt = 0
                while (
                    addonEvidence.observedChapterCount == 0 &&
                    broadenAttempt < MAX_EMPTY_BINDING_BROADEN_ATTEMPTS
                ) {
                    broadenAttempt++
                    val broadened = try {
                        discoverReadableTitle
                            ?.execute(canonicalTitleId, broadenExistingBindings = true)
                            ?.getOrNull()
                            .orEmpty()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        emptyList()
                    }
                    val newBindings = broadened.filter { it.id !in knownBindingIds }
                    if (newBindings.isEmpty()) break
                    knownBindingIds += newBindings.map(ContentBinding::id)
                    addonEvidence = addonEvidence.merge(
                        collectAddonEvidence(canonicalTitleId) { batch ->
                            reconcileStage(batch.evidence, batch.pendingSnapshots)
                        },
                    )
                }
                integrationEvidence to addonEvidence
            }
            val integrationEvidence = collections.first
            val addonEvidence = collections.second
            val evidence = (integrationEvidence.evidence + addonEvidence.evidence)
                .distinctBy(ChapterEvidence::id)

            if (integrationEvidence.complete && addonEvidence.complete && refreshSnapshots != null) {
                val now = clock()
                refreshSnapshots.upsertIfNewer(
                    ChapterRefreshSnapshot(
                        canonicalTitleId = canonicalTitleId,
                        scopeKey = ChapterRefreshSnapshot.TITLE_SCOPE,
                        fingerprint = null,
                        configurationFingerprint = configurationFingerprint,
                        observedAt = now,
                        refreshedAt = now,
                        itemCount = evidence.size,
                    ),
                )
            }

            trace.event(
                subsystem = DiagnosticSubsystem.CHAPTER,
                name = DiagnosticEventName.CHAPTER_EVIDENCE_RECONCILED,
                stage = DiagnosticStage.RECONCILE,
                outcome = DiagnosticOutcome.SUCCEEDED,
                attributes = mapOf(
                    DiagnosticAttribute.ITEM_COUNT to DiagnosticAttributeValue.Number(evidence.size.toLong()),
                ),
            )
            trace.event(
                subsystem = DiagnosticSubsystem.CHAPTER,
                name = DiagnosticEventName.CHAPTER_REFRESH_COMPLETED,
                stage = DiagnosticStage.COMPLETE,
                outcome = DiagnosticOutcome.SUCCEEDED,
                durationMillis = started.elapsedNow().inWholeMilliseconds.coerceAtLeast(0),
                attributes = mapOf(
                    DiagnosticAttribute.ITEM_COUNT to DiagnosticAttributeValue.Number(evidence.size.toLong()),
                ),
            )
            Result.success(Unit)
        } catch (error: CancellationException) {
            if (error is kotlinx.coroutines.TimeoutCancellationException) {
                recordRefreshOutcome(
                    canonicalTitleId = canonicalTitleId,
                    outcome = ChapterInventoryDiagnosticOutcome.TIMEOUT,
                    reason = ChapterInventoryDiagnosticReason.TIMEOUT_FAILURE,
                )
            }
            trace.event(
                subsystem = DiagnosticSubsystem.CHAPTER,
                name = DiagnosticEventName.CHAPTER_REFRESH_COMPLETED,
                stage = DiagnosticStage.COMPLETE,
                outcome = if (error is kotlinx.coroutines.TimeoutCancellationException) {
                    DiagnosticOutcome.TIMEOUT
                } else {
                    DiagnosticOutcome.CANCELLED
                },
                severity = if (error is kotlinx.coroutines.TimeoutCancellationException) {
                    DiagnosticSeverity.WARN
                } else {
                    DiagnosticSeverity.INFO
                },
                durationMillis = started.elapsedNow().inWholeMilliseconds.coerceAtLeast(0),
            )
            throw error
        } catch (error: Throwable) {
            recordRefreshOutcome(
                canonicalTitleId = canonicalTitleId,
                outcome = error.toDiagnosticOutcome(),
                reason = ChapterInventoryDiagnosticFailures.classify(error).second,
            )
            trace.event(
                subsystem = DiagnosticSubsystem.CHAPTER,
                name = DiagnosticEventName.CHAPTER_REFRESH_COMPLETED,
                stage = DiagnosticStage.COMPLETE,
                outcome = DiagnosticOutcome.FAILED,
                severity = DiagnosticSeverity.WARN,
                durationMillis = started.elapsedNow().inWholeMilliseconds.coerceAtLeast(0),
            )
            Result.failure(error)
        }
    }

    /** Reconcile only the newly linked edition, without refreshing all stored language bindings. */
    suspend fun executeForBinding(binding: ContentBinding): Result<Unit> {
        return try {
            require(
                binding.canonicalTitleId.isNotBlank() &&
                    binding.availability == ContentBindingAvailability.AVAILABLE,
            )
            val currentRegistry = checkNotNull(addonRegistry)
            val resolver = checkNotNull(resolveContentBinding)
            currentRegistry.awaitReady()
            val persisted = resolver.existingBindingsForRefresh(binding.canonicalTitleId, binding.addonId)
                .getOrThrow().firstOrNull {
                    it.id == binding.id && it.providerTitleKey == binding.providerTitleKey &&
                        it.availability == ContentBindingAvailability.AVAILABLE
                } ?: error("Selected binding is missing or its internal source is disabled")
            val provider = currentRegistry.chapterProbeProviders()
                .firstOrNull { it.addonId == binding.addonId } as? TargetedChapterProbeProvider
                ?: error("Selected Add-on does not support targeted chapter inventory")
            val observations = provider.probeBinding(persisted).getOrThrow()
            require(
                observations.all {
                    it.canonicalTitleId == binding.canonicalTitleId &&
                        it.producerKind == ProducerKind.ADDON &&
                        it.producerId == binding.addonId.value
                },
            ) { "Targeted inventory belongs to another title or Add-on" }
            reconcileChapterEvidence.execute(binding.canonicalTitleId, observations)
            refreshSnapshots?.invalidateTitle(binding.canonicalTitleId)
            invalidateContentOptionsAfterChapterRefresh(
                invalidateInFlight = {
                    inFlightContentResolution?.invalidateTitleAddon(
                        binding.canonicalTitleId,
                        binding.addonId,
                    )
                },
                invalidateCache = {
                    contentOptionCache?.invalidateTitleAddon(binding.canonicalTitleId, binding.addonId)
                },
            )
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            recordRefreshOutcome(
                canonicalTitleId = binding.canonicalTitleId,
                addonId = binding.addonId.value,
                outcome = error.toDiagnosticOutcome(),
                reason = ChapterInventoryDiagnosticFailures.classify(error).second,
            )
            Result.failure(error)
        }
    }

    private suspend fun collectIntegrationEvidence(
        canonicalTitleId: String,
        onBatch: suspend (EvidenceCollection) -> Unit = {},
    ): EvidenceCollection = coroutineScope {
        val providers = registry.chapterEvidenceProviders()
        val gate = Semaphore(MAX_CONCURRENT_EVIDENCE_PROVIDERS)
        val pending = providers.map { provider ->
            async {
                gate.withPermit {
                    try {
                        provider.evidenceFor(canonicalTitleId)
                            .fold(
                                onSuccess = { observations ->
                                    val accepted = observations.takeIf {
                                        it.all { observation ->
                                            observation.canonicalTitleId == canonicalTitleId
                                        }
                                    }.orEmpty()
                                    EvidenceCollection(
                                        evidence = accepted,
                                        complete = accepted.size == observations.size,
                                    )
                                },
                                onFailure = { error ->
                                    if (error is CancellationException) throw error
                                    recordRefreshOutcome(
                                        canonicalTitleId = canonicalTitleId,
                                        outcome = error.toDiagnosticOutcome(),
                                        received = 0,
                                    )
                                    EvidenceCollection(emptyList(), complete = false)
                                },
                            )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        recordRefreshOutcome(
                            canonicalTitleId = canonicalTitleId,
                            outcome = error.toDiagnosticOutcome(),
                        )
                        EvidenceCollection(emptyList(), complete = false)
                    }
                }
            }
        }

        val evidenceById = linkedMapOf<String, ChapterEvidence>()
        var complete = true
        awaitInCompletionOrder(pending) { batch ->
            onBatch(batch)
            batch.evidence.forEach { observation ->
                evidenceById.putIfAbsent(observation.id, observation)
            }
            complete = complete && batch.complete
        }
        EvidenceCollection(
            evidence = evidenceById.values.toList(),
            complete = complete,
        )
    }

    private suspend fun collectAddonEvidence(
        canonicalTitleId: String,
        onBatch: suspend (AddonEvidenceCollection) -> Unit = {},
    ): AddonEvidenceCollection {
        val addonRegistry = addonRegistry ?: return AddonEvidenceCollection()
        val resolver = resolveContentBinding ?: return AddonEvidenceCollection()
        try {
            addonRegistry.awaitReady()
        } catch (error: CancellationException) {
            if (error is kotlinx.coroutines.TimeoutCancellationException) {
                recordRefreshOutcome(
                    canonicalTitleId = canonicalTitleId,
                    outcome = ChapterInventoryDiagnosticOutcome.TIMEOUT,
                    reason = ChapterInventoryDiagnosticReason.TIMEOUT_FAILURE,
                )
            }
            throw error
        } catch (error: Throwable) {
            recordRefreshOutcome(
                canonicalTitleId = canonicalTitleId,
                outcome = error.toDiagnosticOutcome(),
                reason = ChapterInventoryDiagnosticFailures.classify(error).second,
            )
            throw error
        }

        return coroutineScope {
            val gate = Semaphore(MAX_CONCURRENT_EVIDENCE_PROVIDERS)
            val results = addonRegistry.chapterProbeProviders()
                .map { provider ->
                    async {
                        gate.withPermit {
                            try {
                                val bindings = resolver
                                    .existingBindingsForRefresh(canonicalTitleId, provider.addonId)
                                    .getOrElse { error ->
                                        if (error is CancellationException) throw error
                                        val (outcome, reason) = when (error) {
                                            is ContentBindingConfirmationRequiredException ->
                                                ChapterInventoryDiagnosticOutcome.PARTIAL to
                                                    ChapterInventoryDiagnosticReason.BINDING_CONFIRMATION_REQUIRED
                                            is ContentBindingNotFoundException ->
                                                ChapterInventoryDiagnosticOutcome.NO_BINDING to
                                                    ChapterInventoryDiagnosticReason.NO_BINDING
                                            else -> ChapterInventoryDiagnosticFailures.classify(error)
                                        }
                                        recordRefreshOutcome(
                                            canonicalTitleId = canonicalTitleId,
                                            addonId = provider.addonId.value,
                                            outcome = outcome,
                                            reason = reason,
                                        )
                                        return@withPermit AddonEvidenceCollection(complete = false)
                                    }
                                if (bindings.isEmpty()) {
                                    recordRefreshOutcome(
                                        canonicalTitleId = canonicalTitleId,
                                        addonId = provider.addonId.value,
                                        outcome = ChapterInventoryDiagnosticOutcome.NO_BINDING,
                                        reason = ChapterInventoryDiagnosticReason.NO_BINDING,
                                    )
                                    return@withPermit AddonEvidenceCollection()
                                }

                                val refresh = if (provider is RefreshAwareChapterProbeProvider) {
                                    provider.probeRefresh(canonicalTitleId)
                                } else {
                                    provider.probe(canonicalTitleId).map { observations ->
                                        ChapterProbeRefresh(
                                            evidence = observations,
                                            observedBindingCount = bindings.size,
                                            observedChapterCount = observations.size,
                                        )
                                    }
                                }
                                refresh.fold(
                                    onSuccess = { batch ->
                                        val accepted = batch.evidence.takeIf {
                                            it.all { observation ->
                                                observation.canonicalTitleId == canonicalTitleId
                                            }
                                        }.orEmpty()
                                        recordRefreshOutcome(
                                            canonicalTitleId = canonicalTitleId,
                                            addonId = provider.addonId.value,
                                            outcome = when {
                                                batch.unchangedBindingCount > 0 && accepted.isEmpty() ->
                                                    ChapterInventoryDiagnosticOutcome.SUCCESS
                                                accepted.isEmpty() -> ChapterInventoryDiagnosticOutcome.EMPTY
                                                else -> ChapterInventoryDiagnosticOutcome.SUCCESS
                                            },
                                            received = batch.evidence.size,
                                            accepted = accepted.size,
                                            discarded = batch.evidence.size - accepted.size,
                                        )
                                        AddonEvidenceCollection(
                                            evidence = accepted,
                                            bindingIds = bindings.mapTo(linkedSetOf(), ContentBinding::id),
                                            observedBindingCount = batch.observedBindingCount,
                                            observedChapterCount = batch.observedChapterCount,
                                            pendingSnapshots = batch.pendingSnapshots,
                                            complete = accepted.size == batch.evidence.size,
                                        )
                                    },
                                    onFailure = { error ->
                                        if (error is CancellationException) throw error
                                        recordRefreshOutcome(
                                            canonicalTitleId = canonicalTitleId,
                                            addonId = provider.addonId.value,
                                            outcome = error.toDiagnosticOutcome(),
                                        )
                                        AddonEvidenceCollection(
                                            bindingIds = bindings.mapTo(linkedSetOf(), ContentBinding::id),
                                            complete = false,
                                        )
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
                                AddonEvidenceCollection(complete = false)
                            }
                        }
                    }
                }
            val evidenceById = linkedMapOf<String, ChapterEvidence>()
            val snapshotsByScope = linkedMapOf<String, ChapterRefreshSnapshot>()
            var observedBindingCount = 0
            var observedChapterCount = 0
            var complete = true
            awaitInCompletionOrder(results) { batch ->
                onBatch(batch)
                batch.evidence.forEach { observation ->
                    evidenceById.putIfAbsent(observation.id, observation)
                }
                batch.pendingSnapshots.forEach { snapshot ->
                    snapshotsByScope[snapshot.scopeKey] = snapshot
                }
                observedBindingCount += batch.observedBindingCount
                observedChapterCount += batch.observedChapterCount
                complete = complete && batch.complete
            }
            AddonEvidenceCollection(
                evidence = evidenceById.values.toList(),
                observedBindingCount = observedBindingCount,
                observedChapterCount = observedChapterCount,
                pendingSnapshots = snapshotsByScope.values.toList(),
                complete = complete,
            )
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

    private suspend fun <T> awaitInCompletionOrder(
        deferreds: List<Deferred<T>>,
        onCompleted: suspend (T) -> Unit,
    ) {
        val pending = deferreds.toMutableList()
        while (pending.isNotEmpty()) {
            val (completed, value) = select<Pair<Deferred<T>, T>> {
                pending.forEach { deferred ->
                    deferred.onAwait { result -> deferred to result }
                }
            }
            pending.remove(completed)
            onCompleted(value)
        }
    }

    private suspend fun isFresh(
        canonicalTitleId: String,
        configurationFingerprint: String,
    ): Boolean {
        val repository = refreshSnapshots ?: return false
        val snapshot = repository.get(canonicalTitleId, ChapterRefreshSnapshot.TITLE_SCOPE) ?: return false
        if (snapshot.configurationFingerprint != configurationFingerprint) return false
        val age = clock() - snapshot.refreshedAt
        return age in 0 until CHAPTER_REFRESH_TTL_MILLIS
    }

    private suspend fun refreshConfigurationFingerprint(): String {
        val tokens = mutableListOf<String>()
        registry.chapterEvidenceProviders()
            .mapTo(tokens) { provider -> "integration:${provider.producerId}" }
        addonRegistry?.chapterProbeProviders().orEmpty().forEach { provider ->
            val providerToken = if (provider is RefreshAwareChapterProbeProvider) {
                provider.refreshConfigurationFingerprint()
            } else {
                provider.addonId.value
            }
            tokens += "addon:$providerToken"
        }
        return ChapterRefreshConfigurationFingerprint.compute(tokens)
    }

    private data class EvidenceCollection(
        val evidence: List<ChapterEvidence> = emptyList(),
        val complete: Boolean = true,
    ) {
        fun merge(other: EvidenceCollection): EvidenceCollection {
            if (other.evidence.isEmpty()) return copy(complete = complete && other.complete)
            if (evidence.isEmpty()) return other.copy(complete = complete && other.complete)
            val merged = LinkedHashMap<String, ChapterEvidence>(evidence.size + other.evidence.size)
            evidence.forEach { observation -> merged.putIfAbsent(observation.id, observation) }
            other.evidence.forEach { observation -> merged.putIfAbsent(observation.id, observation) }
            return EvidenceCollection(
                evidence = merged.values.toList(),
                complete = complete && other.complete,
            )
        }
    }

    private data class AddonEvidenceCollection(
        val evidence: List<ChapterEvidence> = emptyList(),
        val bindingIds: Set<String> = emptySet(),
        val observedBindingCount: Int = 0,
        val observedChapterCount: Int = 0,
        val pendingSnapshots: List<ChapterRefreshSnapshot> = emptyList(),
        val complete: Boolean = true,
    ) {
        fun merge(other: AddonEvidenceCollection): AddonEvidenceCollection {
            val mergedEvidence = LinkedHashMap<String, ChapterEvidence>(evidence.size + other.evidence.size)
            evidence.forEach { observation -> mergedEvidence.putIfAbsent(observation.id, observation) }
            other.evidence.forEach { observation -> mergedEvidence.putIfAbsent(observation.id, observation) }

            val mergedSnapshots =
                LinkedHashMap<String, ChapterRefreshSnapshot>(pendingSnapshots.size + other.pendingSnapshots.size)
            pendingSnapshots.forEach { snapshot -> mergedSnapshots[snapshot.scopeKey] = snapshot }
            other.pendingSnapshots.forEach { snapshot -> mergedSnapshots[snapshot.scopeKey] = snapshot }

            return AddonEvidenceCollection(
                evidence = mergedEvidence.values.toList(),
                bindingIds = bindingIds + other.bindingIds,
                observedBindingCount = observedBindingCount + other.observedBindingCount,
                observedChapterCount = observedChapterCount + other.observedChapterCount,
                pendingSnapshots = mergedSnapshots.values.toList(),
                complete = complete && other.complete,
            )
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
                stage = ChapterInventoryDiagnosticStage.CHAPTER_PROBE,
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

    private fun Throwable.toDiagnosticOutcome(): ChapterInventoryDiagnosticOutcome =
        ChapterInventoryDiagnosticFailures.classify(this).first

    private companion object {
        const val MAX_CONCURRENT_EVIDENCE_PROVIDERS = 4
        const val MAX_EMPTY_BINDING_BROADEN_ATTEMPTS = 3
        const val CHAPTER_REFRESH_TTL_MILLIS = 15 * 60 * 1000L
    }
}

internal suspend fun invalidateContentOptionsAfterChapterRefresh(
    invalidateInFlight: suspend () -> Unit,
    invalidateCache: suspend () -> Unit,
) {
    withContext(NonCancellable) {
        invalidateInFlight()
        invalidateCache()
    }
    currentCoroutineContext().ensureActive()
}
