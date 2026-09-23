package eu.kanade.tachiyomi.data.tsuzuki.integration

import io.kotest.matchers.shouldBe
import io.mockk.coAnswers
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
import tachiyomi.domain.tsuzuki.chapter.diagnostics.NoOpChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentBindingAvailability
import tachiyomi.domain.tsuzuki.content.interactor.ResolveContentBinding
import tachiyomi.domain.tsuzuki.content.repository.ContentBindingRepository
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.source.interactor.ScoreSourceTitleMatch

class MihonBindingHttpIntegrationTest {

    @Test
    fun `local extension response resolves and persists binding for canonical title`() = runTest {
        LocalMihonSourceHarness().use { harness ->
            val addonId = AddonId("fixture-addon")
            val titleId = "canonical-opm"
            val sourceUrl = "/manga/one-punch-man"
            harness.enqueue(body = "$sourceUrl\tOne-Punch Man")
            coEvery { harness.mangaRepository.insertNetworkManga(any()) } coAnswers {
                firstArg<List<Manga>>().map { it.copy(id = 9001L) }
            }

            val bindings = InMemoryBindings()
            val canonicalTitles = mockk<CanonicalTitleRepository> {
                coEvery { getById(titleId) } returns CanonicalTitle(
                    id = titleId,
                    displayTitle = "One-Punch Man",
                    identityState = CanonicalIdentityState.RESOLVED,
                    createdAt = 1L,
                    updatedAt = 1L,
                )
            }
            val addonRepository = mockk<AddonRepository> {
                coEvery { snapshot() } returns listOf(
                    InstalledAddon(
                        id = addonId,
                        displayName = "Fixture Add-on",
                        enabled = true,
                        versionName = "test",
                        mihonSourceIds = listOf(harness.source.id),
                        hasSettings = false,
                    ),
                )
            }
            val resolver = ResolveContentBinding(
                contentBindingRepository = bindings,
                canonicalTitleRepository = canonicalTitles,
                addonRepository = addonRepository,
                readingSourceGateway = harness.gateway,
                scoreSourceTitleMatch = ScoreSourceTitleMatch(),
                idFactory = { "binding-1" },
                clock = { 42L },
                diagnostics = NoOpChapterInventoryDiagnostics,
            )

            val resolved = resolver.executeAll(titleId, addonId).getOrThrow().single()

            resolved.canonicalTitleId shouldBe titleId
            resolved.addonId shouldBe addonId
            resolved.providerTitleKey shouldBe "${harness.source.id}:$sourceUrl"
            resolved.availability shouldBe ContentBindingAvailability.AVAILABLE
            bindings.getByTitle(titleId).single() shouldBe resolved
        }
    }

    private class InMemoryBindings : ContentBindingRepository {
        private val bindings = mutableListOf<ContentBinding>()

        override suspend fun get(canonicalTitleId: String, addonId: AddonId): ContentBinding? =
            bindings.firstOrNull { it.canonicalTitleId == canonicalTitleId && it.addonId == addonId }

        override suspend fun getByTitle(canonicalTitleId: String): List<ContentBinding> =
            bindings.filter { it.canonicalTitleId == canonicalTitleId }

        override suspend fun upsert(binding: ContentBinding) {
            bindings.removeAll { it.id == binding.id }
            bindings += binding
        }

        override suspend fun markUnavailable(bindingId: String, updatedAt: Long) {
            bindings.replaceAll { binding ->
                if (binding.id == bindingId) {
                    binding.copy(availability = ContentBindingAvailability.UNAVAILABLE, updatedAt = updatedAt)
                } else {
                    binding
                }
            }
        }
    }
}
