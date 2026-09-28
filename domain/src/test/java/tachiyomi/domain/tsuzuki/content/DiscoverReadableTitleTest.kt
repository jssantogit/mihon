package tachiyomi.domain.tsuzuki.content

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon
import tachiyomi.domain.tsuzuki.addon.repository.AddonSourceEligibility
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSearchProgress
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSourceOutcome
import tachiyomi.domain.tsuzuki.content.interactor.DiscoverReadableTitle
import tachiyomi.domain.tsuzuki.content.interactor.PlanFastReadingDiscovery

class DiscoverReadableTitleTest {

    @Test
    fun `configured preferred source is searched before a smaller unrelated addon`() = runTest {
        val preferred = installed("preferred", 42L)
        val smaller = installed("small", 7L)
        val searches = mutableListOf<Set<Long>?>()
        val discover = DiscoverReadableTitle(
            hasObservedChapters = { false },
            existingBindings = { emptyList() },
            installedAddons = { listOf(smaller, preferred) },
            sourceEligibility = { addonId ->
                if (addonId == preferred.id) listOf(source(42L, "en")) else listOf(source(7L, "en"))
            },
            preferredLanguages = { listOf("en") },
            preferredSourceIds = { listOf(42L, 7L) },
            sourceSearch = { request ->
                searches += request.allowedSourceIds
                flow {
                    val sourceId = requireNotNull(request.allowedSourceIds).single()
                    if (sourceId == 42L) {
                        emit(
                            ContentBindingSearchProgress.SourceCompleted(
                                sourceId = sourceId,
                                language = "en",
                                outcome = ContentBindingSourceOutcome.BOUND,
                                bindings = listOf(binding(preferred.id, sourceId)),
                            ),
                        )
                    } else {
                        emit(
                            ContentBindingSearchProgress.SourceCompleted(
                                sourceId = sourceId,
                                language = "en",
                                outcome = ContentBindingSourceOutcome.EMPTY,
                            ),
                        )
                    }
                    emit(ContentBindingSearchProgress.Completed(listOf(sourceId), 0))
                }
            },
            planner = PlanFastReadingDiscovery(),
        )

        val bindings = discover.execute("title").getOrThrow()

        searches.first() shouldBe setOf(42L)
        bindings.map { it.providerTitleKey } shouldBe listOf("42:/title")
    }

    @Test
    fun `observed chapter evidence skips automatic title discovery`() = runTest {
        var searches = 0
        val addon = installed("preferred", 42L)
        val discover = DiscoverReadableTitle(
            hasObservedChapters = { true },
            existingBindings = { emptyList() },
            installedAddons = { listOf(addon) },
            sourceEligibility = { listOf(source(42L, "en")) },
            preferredLanguages = { listOf("en") },
            preferredSourceIds = { listOf(42L) },
            sourceSearch = {
                searches++
                flow { }
            },
            planner = PlanFastReadingDiscovery(),
        )

        discover.execute("title").getOrThrow() shouldBe emptyList()
        searches shouldBe 0
    }

    private fun installed(name: String, vararg sourceIds: Long) = InstalledAddon(
        id = AddonId(name),
        displayName = name,
        enabled = true,
        versionName = "1.0",
        mihonSourceIds = sourceIds.toList(),
        hasSettings = false,
    )

    private fun source(sourceId: Long, language: String) =
        AddonSourceEligibility(sourceId, language, enabled = true)

    private fun binding(addonId: AddonId, sourceId: Long) = ContentBinding(
        id = "binding-$sourceId",
        canonicalTitleId = "title",
        addonId = addonId,
        providerTitleKey = "$sourceId:/title",
        matchConfidence = 1.0,
        verifiedByUser = false,
        availability = ContentBindingAvailability.AVAILABLE,
        runtimePayload = byteArrayOf(1),
        createdAt = 1L,
        updatedAt = 1L,
    )
}
