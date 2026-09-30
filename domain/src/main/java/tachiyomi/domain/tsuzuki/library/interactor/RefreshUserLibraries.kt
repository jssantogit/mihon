package tachiyomi.domain.tsuzuki.library.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.UserListProvider
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitleFromCatalog
import tachiyomi.domain.tsuzuki.library.model.ExternalLibraryMembership
import tachiyomi.domain.tsuzuki.repository.ExternalLibraryRepository
import kotlin.time.Clock

class RefreshUserLibraries internal constructor(
    private val registry: IntegrationRegistry,
    private val materializeCanonicalTitleFromCatalog: MaterializeCanonicalTitleFromCatalog,
    private val externalLibraryRepository: ExternalLibraryRepository,
    private val clock: () -> Long,
) {

    @Inject
    constructor(
        registry: IntegrationRegistry,
        materializeCanonicalTitleFromCatalog: MaterializeCanonicalTitleFromCatalog,
        externalLibraryRepository: ExternalLibraryRepository,
    ) : this(
        registry = registry,
        materializeCanonicalTitleFromCatalog = materializeCanonicalTitleFromCatalog,
        externalLibraryRepository = externalLibraryRepository,
        clock = { Clock.System.now().toEpochMilliseconds() },
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

    suspend fun clearProvider(integrationId: IntegrationId) {
        externalLibraryRepository.clearProvider(integrationId.value)
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
            val entries = snapshot.getOrThrow().entries
            val memberships = entries.flatMap { entry ->
                val canonicalTitle = materializeCanonicalTitleFromCatalog.execute(entry.item)
                entry.listKeys.map { listKey ->
                    ExternalLibraryMembership(
                        canonicalTitleId = canonicalTitle.id,
                        provider = provider.integrationId.value,
                        externalId = entry.item.providerId,
                        listKey = listKey,
                        status = entry.status,
                        remoteStatus = entry.remoteStatus,
                        progress = entry.progress,
                        score = entry.score,
                        listedAt = entry.listedAt,
                        syncedAt = syncedAt,
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
