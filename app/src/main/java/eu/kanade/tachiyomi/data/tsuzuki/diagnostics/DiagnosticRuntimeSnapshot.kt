package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.repository.ExternalLibraryRepository

@Inject
@SingleIn(AppScope::class)
class DiagnosticRuntimeSnapshot(
    private val addonRepository: AddonRepository,
    private val integrationRegistry: IntegrationRegistry,
    private val canonicalTitleRepository: CanonicalTitleRepository,
    private val externalLibraryRepository: ExternalLibraryRepository,
) {
    suspend fun build(): String {
        val addons = bestEffort { addonRepository.snapshot() }
        val integrationCount = bestEffort {
            integrationRegistry.awaitReady()
            integrationRegistry.manifests().size
        }
        val connectedAccounts = bestEffort {
            integrationRegistry.awaitReady()
            integrationRegistry.userListProviders().count { provider ->
                bestEffort { provider.connection.first() }.getOrNull() == true
            }
        }
        val canonicalTitles = bestEffort { canonicalTitleRepository.getAllAsFlow().first().size }
        val externalMemberships = bestEffort { externalLibraryRepository.observeAll().first().size }

        return buildString {
            appendLine("installed_addons=${addons.getOrNull()?.size ?: UNAVAILABLE}")
            appendLine("enabled_addons=${addons.getOrNull()?.count { it.enabled } ?: UNAVAILABLE}")
            appendLine("registered_integrations=${integrationCount.getOrNull() ?: UNAVAILABLE}")
            appendLine("connected_account_providers=${connectedAccounts.getOrNull() ?: UNAVAILABLE}")
            appendLine("canonical_titles=${canonicalTitles.getOrNull() ?: UNAVAILABLE}")
            append("external_library_memberships=${externalMemberships.getOrNull() ?: UNAVAILABLE}")
        }
    }

    private suspend fun <T> bestEffort(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private companion object {
        const val UNAVAILABLE = "unavailable"
    }
}
