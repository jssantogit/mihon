package tachiyomi.domain.tsuzuki.library.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
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
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.UserListProvider
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitleFromCatalog
import tachiyomi.domain.tsuzuki.library.model.ExternalLibraryMembership
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.repository.ExternalLibraryRepository
import kotlin.time.Clock
import kotlin.time.TimeSource

class RefreshUserLibraries internal constructor(
    private val registry: IntegrationRegistry,
    private val resolveCanonicalTitle: suspend (CatalogItem, Long?) -> CanonicalTitle,
    private val externalLibraryRepository: ExternalLibraryRepository,
    private val clock: () -> Long,
    private val diagnosticRecorder: StructuredDiagnosticRecorder,
) {

    @Inject
    constructor(
        registry: IntegrationRegistry,
        resolveUserLibraryCanonicalTitle: ResolveUserLibraryCanonicalTitle,
        externalLibraryRepository: ExternalLibraryRepository,
        diagnosticRecorder: StructuredDiagnosticRecorder,
    ) : this(
        registry = registry,
        resolveCanonicalTitle = resolveUserLibraryCanonicalTitle::execute,
        externalLibraryRepository = externalLibraryRepository,
        clock = { Clock.System.now().toEpochMilliseconds() },
        diagnosticRecorder = diagnosticRecorder,
    )

    internal constructor(
        registry: IntegrationRegistry,
        materializeCanonicalTitleFromCatalog: MaterializeCanonicalTitleFromCatalog,
        externalLibraryRepository: ExternalLibraryRepository,
        clock: () -> Long,
    ) : this(
        registry = registry,
        resolveCanonicalTitle = { item, _ ->
            materializeCanonicalTitleFromCatalog.execute(item)
        },
        externalLibraryRepository = externalLibraryRepository,
        clock = clock,
        diagnosticRecorder = NoOpStructuredDiagnosticRecorder,
    )

    suspend fun refreshConnected(): Map<IntegrationId, Result<Int>> {
        registry.awaitReady()
        val providers = registry.userListProviders()
        val enabledProviderIds = providers.mapTo(mutableSetOf()) { it.integrationId.value }

        externalLibraryRepository.getProviderIds()
            .filterNot(enabledProviderIds::contains)
            .forEach { provider -> externalLibraryRepository.clearProvider(provider) }

        return buildMap {
            providers.forEach { provider ->
                val result = if (provider.connection.first()) {
                    refresh(provider)
                } else {
                    externalLibraryRepository.clearProvider(provider.integrationId.value)
                    Result.success(0)
                }
                put(provider.integrationId, result)
            }
        }
    }

    suspend fun refreshProvider(integrationId: IntegrationId): Result<Int> {
        registry.awaitReady()
        val provider = registry.userListProviders()
            .firstOrNull { it.integrationId == integrationId }
            ?: return Result.failure(
                IllegalArgumentException("No user-list provider for ${integrationId.value}"),
            )

        if (!provider.connection.first()) {
            externalLibraryRepository.clearProvider(integrationId.value)
            return Result.success(0)
        }

        return refresh(provider)
    }

    suspend fun refreshForLegacyTracker(trackerId: Long): Result<Int>? {
        val integrationId = integrationIdForLegacyTracker(trackerId) ?: return null
        return refreshProvider(integrationId)
    }

    suspend fun clearForLegacyTracker(trackerId: Long) {
        integrationIdForLegacyTracker(trackerId)?.let { integrationId ->
            clearProvider(integrationId)
        }
    }

    suspend fun clearProvider(integrationId: IntegrationId) {
        externalLibraryRepository.clearProvider(integrationId.value)
    }

    private fun integrationIdForLegacyTracker(trackerId: Long): IntegrationId? {
        return registry.manifests()
            .firstOrNull { manifest ->
                manifest.legacyTrackerId == trackerId &&
                    manifest.declares(IntegrationCapability.USER_LISTS)
            }
            ?.integrationId
    }

    private suspend fun refresh(provider: UserListProvider): Result<Int> {
        val trace = DiagnosticTrace.start(
            recorder = diagnosticRecorder,
            workflow = DiagnosticWorkflow.LIBRARY_SYNC,
            subsystem = DiagnosticSubsystem.LIBRARY,
        )
        val started = TimeSource.Monotonic.markNow()
        val providerAttributes = mapOf(
            DiagnosticAttribute.PROVIDER_ID to DiagnosticAttributeValue.Text(provider.integrationId.value),
        )
        trace.event(
            subsystem = DiagnosticSubsystem.LIBRARY,
            name = DiagnosticEventName.LIBRARY_SYNC_STARTED,
            stage = DiagnosticStage.SYNC,
            outcome = DiagnosticOutcome.STARTED,
            attributes = providerAttributes,
        )

        val snapshot = provider.fetchLibrary()
        val error = snapshot.exceptionOrNull()
        if (error != null) {
            if (error is CancellationException) throw error
            trace.event(
                subsystem = DiagnosticSubsystem.LIBRARY,
                name = DiagnosticEventName.LIBRARY_SYNC_COMPLETED,
                stage = DiagnosticStage.COMPLETE,
                outcome = DiagnosticOutcome.FAILED,
                severity = DiagnosticSeverity.WARN,
                durationMillis = started.elapsedNow().inWholeMilliseconds.coerceAtLeast(0),
                attributes = providerAttributes,
            )
            return Result.failure(error)
        }

        return try {
            val syncedAt = clock()
            val librarySnapshot = snapshot.getOrThrow()
            val entries = librarySnapshot.entries
            trace.event(
                subsystem = DiagnosticSubsystem.LIBRARY,
                name = DiagnosticEventName.LIBRARY_PROVIDER_FETCHED,
                stage = DiagnosticStage.READ,
                outcome = DiagnosticOutcome.SUCCEEDED,
                attributes = providerAttributes + mapOf(
                    DiagnosticAttribute.ITEM_COUNT to DiagnosticAttributeValue.Number(entries.size.toLong()),
                ),
            )
            val listsByKey = librarySnapshot.lists.associateBy { it.key }
            val legacyTrackerId = registry.manifests()
                .firstOrNull { it.integrationId == provider.integrationId }
                ?.legacyTrackerId
            val memberships = entries.flatMap { entry ->
                val canonicalTitle = resolveCanonicalTitle(entry.item, legacyTrackerId)
                entry.listKeys.map { listKey ->
                    val listDefinition = listsByKey[listKey]
                    ExternalLibraryMembership(
                        canonicalTitleId = canonicalTitle.id,
                        provider = provider.integrationId.value,
                        externalId = entry.item.providerId,
                        listKey = listKey,
                        listTitle = listDefinition?.title,
                        selectionGroup = listDefinition?.selectionGroup,
                        status = entry.status,
                        remoteStatus = entry.remoteStatus,
                        progress = entry.progress,
                        score = entry.score,
                        listedAt = entry.listedAt,
                        syncedAt = syncedAt,
                        coverUrl = entry.item.coverUrl,
                    )
                }
            }

            externalLibraryRepository.replaceProvider(
                provider = provider.integrationId.value,
                memberships = memberships,
            )
            trace.event(
                subsystem = DiagnosticSubsystem.LIBRARY,
                name = DiagnosticEventName.LIBRARY_SYNC_COMPLETED,
                stage = DiagnosticStage.COMPLETE,
                outcome = DiagnosticOutcome.SUCCEEDED,
                durationMillis = started.elapsedNow().inWholeMilliseconds.coerceAtLeast(0),
                attributes = providerAttributes + mapOf(
                    DiagnosticAttribute.ITEM_COUNT to DiagnosticAttributeValue.Number(entries.size.toLong()),
                    DiagnosticAttribute.ACCEPTED_COUNT to DiagnosticAttributeValue.Number(memberships.size.toLong()),
                ),
            )
            Result.success(entries.size)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            trace.event(
                subsystem = DiagnosticSubsystem.LIBRARY,
                name = DiagnosticEventName.LIBRARY_SYNC_COMPLETED,
                stage = DiagnosticStage.COMPLETE,
                outcome = DiagnosticOutcome.FAILED,
                severity = DiagnosticSeverity.WARN,
                durationMillis = started.elapsedNow().inWholeMilliseconds.coerceAtLeast(0),
                attributes = providerAttributes,
            )
            Result.failure(error)
        }
    }
}
