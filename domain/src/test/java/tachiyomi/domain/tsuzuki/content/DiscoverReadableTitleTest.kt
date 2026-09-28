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
    fun `automatic title discovery broadens after the first bounded batch is empty`() = runTest {
        val first = installed("a-first", 1L)
        val second = installed("b-second", 2L)
        val fallback = installed("c-fallback", 3L)
        val searched = mutableListOf<Long>()
        val discover = DiscoverReadableTitle(
            existingBindings = { emptyList() },
            installedAddons = { listOf(first, second, fallback) },
            sourceEligibility = { addonId ->
                when (addonId) {
                    first.id -> listOf(source(1L, "en"))
                    second.id -> listOf(source(2L, "en"))
                    else -> listOf(source(3L, "en"))
                }
            },
            preferredLanguages = { listOf("en") },
            preferredSourceIds = { emptyList() },
            sourceSearch = { request ->
                flow {
                    val sourceId = requireNotNull(request.allowedSourceIds).single()
                    searched += sourceId
                    emit(
                        ContentBindingSearchProgress.SourceCompleted(
                            sourceId = sourceId,
                            language = "en",
                            outcome = if (sourceId == 3L) {
                                ContentBindingSourceOutcome.BOUND
                            } else {
                                ContentBindingSourceOutcome.EMPTY
                            },
                            bindings = if (sourceId == 3L) {
                                listOf(binding(fallback.id, sourceId))
                            } else {
                                emptyList()
                            },
                        ),
                    )
                    emit(ContentBindingSearchProgress.Completed(listOf(sourceId), 0))
                }
            },
            planner = PlanFastReadingDiscovery(),
        )

        val bindings = discover.execute("title").getOrThrow()

        bindings.map { it.providerTitleKey } shouldBe listOf("3:/title")
        searched.take(2).toSet() shouldBe setOf(1L, 2L)
        searched.drop(2) shouldBe listOf(3L)
    }

    @Test
    fun `successful first wave stops before querying unrelated fallback sources`() = runTest {
        val preferred = installed("a-preferred", 1L)
        val peer = installed("b-peer", 2L)
        val fallback = installed("c-fallback", 3L)
        val searched = mutableListOf<Long>()
        val discover = DiscoverReadableTitle(
            existingBindings = { emptyList() },
            installedAddons = { listOf(preferred, peer, fallback) },
            sourceEligibility = { addonId ->
                when (addonId) {
                    preferred.id -> listOf(source(1L, "en"))
                    peer.id -> listOf(source(2L, "en"))
                    else -> listOf(source(3L, "en"))
                }
            },
            preferredLanguages = { listOf("en") },
            preferredSourceIds = { listOf(1L) },
            sourceSearch = { request ->
                flow {
                    val sourceId = requireNotNull(request.allowedSourceIds).single()
                    searched += sourceId
                    emit(
                        ContentBindingSearchProgress.SourceCompleted(
                            sourceId = sourceId,
                            language = "en",
                            outcome = if (sourceId == 1L) {
                                ContentBindingSourceOutcome.BOUND
                            } else {
                                ContentBindingSourceOutcome.EMPTY
                            },
                            bindings = if (sourceId == 1L) {
                                listOf(binding(preferred.id, sourceId))
                            } else {
                                emptyList()
                            },
                        ),
                    )
                    emit(ContentBindingSearchProgress.Completed(listOf(sourceId), 0))
                }
            },
            planner = PlanFastReadingDiscovery(),
        )

        discover.execute("title").getOrThrow().single().providerTitleKey shouldBe "1:/title"
        searched.toSet() shouldBe setOf(1L, 2L)
        searched.contains(3L) shouldBe false
    }

    @Test
    fun `one eligibility failure does not suppress a healthy configured source`() = runTest {
        val broken = installed("a-broken", 1L)
        val healthy = installed("b-healthy", 2L)
        val discover = DiscoverReadableTitle(
            existingBindings = { emptyList() },
            installedAddons = { listOf(broken, healthy) },
            sourceEligibility = { addonId ->
                if (addonId == broken.id) error("broken extension metadata")
                listOf(source(2L, "en"))
            },
            preferredLanguages = { listOf("en") },
            preferredSourceIds = { listOf(2L) },
            sourceSearch = { request ->
                flow {
                    val sourceId = requireNotNull(request.allowedSourceIds).single()
                    emit(
                        ContentBindingSearchProgress.SourceCompleted(
                            sourceId = sourceId,
                            language = "en",
                            outcome = ContentBindingSourceOutcome.BOUND,
                            bindings = listOf(binding(healthy.id, sourceId)),
                        ),
                    )
                    emit(ContentBindingSearchProgress.Completed(listOf(sourceId), 0))
                }
            },
            planner = PlanFastReadingDiscovery(),
        )

        discover.execute("title").getOrThrow().single().providerTitleKey shouldBe "2:/title"
    }

    @Test
    fun `per-title preferred addon outranks package-size fallback when no global source is configured`() = runTest {
        val preferred = installed("preferred-large", 10L, 11L, 12L)
        val small = installed("small", 20L)
        val searched = mutableListOf<AddonId>()
        val discover = DiscoverReadableTitle(
            existingBindings = { emptyList() },
            installedAddons = { listOf(small, preferred) },
            sourceEligibility = { addonId ->
                if (addonId == preferred.id) listOf(source(10L, "en")) else listOf(source(20L, "en"))
            },
            preferredLanguages = { listOf("en") },
            preferredSourceIds = { emptyList() },
            preferredAddonId = { preferred.id },
            sourceSearch = { request ->
                searched += request.addonId
                flow {
                    val sourceId = requireNotNull(request.allowedSourceIds).single()
                    emit(
                        ContentBindingSearchProgress.SourceCompleted(
                            sourceId = sourceId,
                            language = "en",
                            outcome = ContentBindingSourceOutcome.BOUND,
                            bindings = listOf(binding(request.addonId, sourceId)),
                        ),
                    )
                    emit(ContentBindingSearchProgress.Completed(listOf(sourceId), 0))
                }
            },
            planner = PlanFastReadingDiscovery(),
        )

        discover.execute("title").getOrThrow()
        searched.first() shouldBe preferred.id
    }

    @Test
    fun `empty existing binding can broaden to another configured source`() = runTest {
        val stale = installed("stale", 1L)
        val readable = installed("readable", 2L)
        val existing = binding(stale.id, 1L)
        val searched = mutableListOf<Long>()
        val discover = DiscoverReadableTitle(
            existingBindings = { listOf(existing) },
            installedAddons = { listOf(stale, readable) },
            sourceEligibility = { addonId ->
                if (addonId == stale.id) listOf(source(1L, "en")) else listOf(source(2L, "pt-BR"))
            },
            preferredLanguages = { listOf("pt-BR", "en") },
            preferredSourceIds = { listOf(2L, 1L) },
            sourceSearch = { request ->
                flow {
                    val sourceId = requireNotNull(request.allowedSourceIds).single()
                    searched += sourceId
                    emit(
                        ContentBindingSearchProgress.SourceCompleted(
                            sourceId = sourceId,
                            language = if (sourceId == 2L) "pt-BR" else "en",
                            outcome = if (sourceId == 2L) {
                                ContentBindingSourceOutcome.BOUND
                            } else {
                                ContentBindingSourceOutcome.EMPTY
                            },
                            bindings = if (sourceId == 2L) {
                                listOf(binding(readable.id, 2L))
                            } else {
                                emptyList()
                            },
                        ),
                    )
                    emit(ContentBindingSearchProgress.Completed(listOf(sourceId), 0))
                }
            },
            planner = PlanFastReadingDiscovery(),
        )

        val bindings = discover.execute(
            canonicalTitleId = "title",
            broadenExistingBindings = true,
        ).getOrThrow()

        searched shouldBe listOf(2L)
        bindings.map { it.providerTitleKey }.toSet() shouldBe setOf("1:/title", "2:/title")
    }

    @Test
    fun `existing binding still discovers missing configured reading source without probing unrelated fallback`() =
        runTest {
            val existingAddon = installed("existing", 1L)
            val configuredAddon = installed("configured", 2L)
            val unrelatedAddon = installed("unrelated", 3L)
            val existing = binding(existingAddon.id, 1L)
            val searched = mutableListOf<Long>()
            val discover = DiscoverReadableTitle(
                existingBindings = { listOf(existing) },
                installedAddons = { listOf(existingAddon, configuredAddon, unrelatedAddon) },
                sourceEligibility = { addonId ->
                    when (addonId) {
                        existingAddon.id -> listOf(source(1L, "en"))
                        configuredAddon.id -> listOf(source(2L, "pt-BR"))
                        else -> listOf(source(3L, "pt-BR"))
                    }
                },
                preferredLanguages = { listOf("pt-BR", "en") },
                preferredSourceIds = { listOf(2L, 1L) },
                sourceSearch = { request ->
                    flow {
                        val sourceId = requireNotNull(request.allowedSourceIds).single()
                        searched += sourceId
                        emit(
                            ContentBindingSearchProgress.SourceCompleted(
                                sourceId = sourceId,
                                language = "pt-BR",
                                outcome = ContentBindingSourceOutcome.BOUND,
                                bindings = listOf(binding(configuredAddon.id, sourceId)),
                            ),
                        )
                        emit(ContentBindingSearchProgress.Completed(listOf(sourceId), 0))
                    }
                },
                planner = PlanFastReadingDiscovery(),
            )

            val bindings = discover.execute("title").getOrThrow()

            bindings.map { it.providerTitleKey }.toSet() shouldBe setOf("1:/title", "2:/title")
            searched shouldBe listOf(2L)
            searched.contains(3L) shouldBe false
        }

    @Test
    fun `existing usable reading binding stops title discovery immediately`() = runTest {
        var searches = 0
        val addon = installed("preferred", 42L)
        val existing = binding(addon.id, 42L)
        val discover = DiscoverReadableTitle(
            existingBindings = { listOf(existing) },
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

        discover.execute("title").getOrThrow() shouldBe listOf(existing)
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
