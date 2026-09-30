package tachiyomi.domain.tsuzuki.library.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.UserListProvider
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitleFromCatalog
import tachiyomi.domain.tsuzuki.library.model.ExternalLibraryMembership
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.repository.ExternalLibraryRepository
import kotlin.time.Clock

class RefreshUserLibraries internal constructor(
    private val registry: IntegrationRegistry,
    private val resolveCanonicalTitle: suspend (CatalogItem, Long?) -> CanonicalTitle,
    private val externalLibraryRepository: ExternalLibraryRepository,
    private val clock: () -> Long,
) {

    @Inject
    constructor(
        registry: IntegrationRegistry,
        resolveUserLibraryCanonicalTitle: ResolveUserLibraryCanonicalTitle,
        externalLibraryRepository: ExternalLibraryRepository,
    ) : this(
        registry = registry,
        resolveCanonicalTitle = resolveUserLibraryCanonicalTitle::execute,
        externalLibraryRepository = externalLibraryRepository,
        clock = { Clock.System.now().toEpochMilliseconds() },
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
        val snapshot = provider.fetchLibrary()
        val error = snapshot.exceptionOrNull()
        if (error != null) {
            if (error is CancellationException) throw error
            return Result.failure(error)
        }

        return try {
            val syncedAt = clock()
            val librarySnapshot = snapshot.getOrThrow()
            val entries = librarySnapshot.entries
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
            Result.success(entries.size)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }
}
