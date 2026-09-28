package tachiyomi.domain.tsuzuki.chapter.evidence

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.AddonRegistry
import tachiyomi.domain.tsuzuki.addon.TargetedChapterProbeProvider
import tachiyomi.domain.tsuzuki.chapter.diagnostics.NoOpChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentBindingAvailability
import tachiyomi.domain.tsuzuki.content.cache.ContentOptionCache
import tachiyomi.domain.tsuzuki.content.cache.ContentOptionCacheKey
import tachiyomi.domain.tsuzuki.content.interactor.ResolveContentBinding
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry

class RefreshBindingEvidenceTest {

    @Test
    fun `binding refresh probes one edition and preserves cache for unrelated addons`() = runTest {
        val binding = binding()
        val other = AddonId("unrelated")
        val targetedKey = ContentOptionCacheKey("title", "chapter", binding.addonId)
        val otherKey = ContentOptionCacheKey("title", "chapter", other)
        val cache = ContentOptionCache()
        cache.put(targetedKey, emptyList())
        cache.put(otherKey, emptyList())
        val observed = ChapterEvidence(
            id = "new-evidence",
            canonicalTitleId = "title",
            producerKind = ProducerKind.ADDON,
            producerId = binding.addonId.value,
            externalChapterKey = "7:/one",
            rawLabel = "Chapter 1",
            rawNumber = 1.0,
            volume = null,
            title = null,
            observedAt = 2L,
            confidence = 1.0,
            authority = ChapterEvidenceAuthority.ADDON_PROVISIONAL,
        )
        var scopedCalls = 0
        val provider = object : TargetedChapterProbeProvider {
            override val addonId = binding.addonId
            override suspend fun probe(canonicalTitleId: String): Result<List<ChapterEvidence>> =
                error("Full package probe must not run")

            override suspend fun probeBinding(binding: ContentBinding): Result<List<ChapterEvidence>> {
                scopedCalls++
                return Result.success(listOf(observed))
            }
        }
        val addons = mockk<AddonRegistry>()
        coEvery { addons.awaitReady() } returns Unit
        every { addons.chapterProbeProviders() } returns listOf(provider)
        val resolver = mockk<ResolveContentBinding>()
        coEvery { resolver.existingBindingsForRefresh("title", binding.addonId) } returns
            Result.success(listOf(binding))
        val reconciler = mockk<ReconcileChapterEvidence>()
        coEvery { reconciler.execute("title", any()) } returns Unit
        val refresh = RefreshChapterEvidence(
            registry = mockk<IntegrationRegistry>(relaxed = true),
            reconcileChapterEvidence = reconciler,
            addonRegistry = addons,
            resolveContentBinding = resolver,
            contentOptionCache = cache,
            diagnostics = NoOpChapterInventoryDiagnostics,
        )

        refresh.executeForBinding(binding).isSuccess shouldBe true

        scopedCalls shouldBe 1
        coVerify(exactly = 1) { reconciler.execute("title", match { it == listOf(observed) }) }
        cache.get(targetedKey) shouldBe null
        cache.get(otherKey) shouldBe emptyList()
    }

    @Test
    fun `disabled binding is rejected without probing or evicting cached options`() = runTest {
        val binding = binding()
        val key = ContentOptionCacheKey("title", "chapter", binding.addonId)
        val cache = ContentOptionCache()
        cache.put(key, emptyList())
        val addons = mockk<AddonRegistry>()
        coEvery { addons.awaitReady() } returns Unit
        val resolver = mockk<ResolveContentBinding>()
        coEvery { resolver.existingBindingsForRefresh("title", binding.addonId) } returns
            Result.success(emptyList())
        val refresh = RefreshChapterEvidence(
            registry = mockk<IntegrationRegistry>(relaxed = true),
            reconcileChapterEvidence = mockk<ReconcileChapterEvidence>(relaxed = true),
            addonRegistry = addons,
            resolveContentBinding = resolver,
            contentOptionCache = cache,
            diagnostics = NoOpChapterInventoryDiagnostics,
        )

        refresh.executeForBinding(binding).isFailure shouldBe true

        cache.get(key) shouldBe emptyList()
    }

    private fun binding() = ContentBinding(
        id = "new-binding",
        canonicalTitleId = "title",
        addonId = AddonId("multi"),
        providerTitleKey = "7:/one",
        matchConfidence = 0.99,
        verifiedByUser = false,
        availability = ContentBindingAvailability.AVAILABLE,
        runtimePayload = byteArrayOf(1),
        createdAt = 1L,
        updatedAt = 1L,
    )
}
