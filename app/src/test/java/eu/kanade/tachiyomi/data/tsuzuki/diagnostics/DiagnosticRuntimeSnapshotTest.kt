package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.UserListProvider
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.repository.ExternalLibraryRepository

class DiagnosticRuntimeSnapshotTest {
    @Test
    fun `snapshot reports only safe local runtime counts`() = runTest {
        val addons = mockk<AddonRepository>()
        coEvery { addons.snapshot() } returns listOf(
            mockk { every { enabled } returns true },
            mockk { every { enabled } returns false },
            mockk { every { enabled } returns true },
        )

        val connectedProvider = mockk<UserListProvider> {
            every { connection } returns flowOf(true)
        }
        val disconnectedProvider = mockk<UserListProvider> {
            every { connection } returns flowOf(false)
        }
        val integrations = mockk<IntegrationRegistry> {
            coEvery { awaitReady() } returns Unit
            every { manifests() } returns listOf(mockk(), mockk(), mockk(), mockk())
            every { userListProviders() } returns listOf(connectedProvider, disconnectedProvider)
        }

        val titles = mockk<CanonicalTitleRepository> {
            every { getAllAsFlow() } returns flowOf(listOf(mockk(), mockk(), mockk()))
        }
        val library = mockk<ExternalLibraryRepository> {
            every { observeAll() } returns flowOf(listOf(mockk(), mockk()))
        }

        val snapshot = DiagnosticRuntimeSnapshot(
            addonRepository = addons,
            integrationRegistry = integrations,
            canonicalTitleRepository = titles,
            externalLibraryRepository = library,
        ).build()

        assertTrue(snapshot.contains("installed_addons=3"))
        assertTrue(snapshot.contains("enabled_addons=2"))
        assertTrue(snapshot.contains("registered_integrations=4"))
        assertTrue(snapshot.contains("connected_account_providers=1"))
        assertTrue(snapshot.contains("canonical_titles=3"))
        assertTrue(snapshot.contains("external_library_memberships=2"))
    }
}
