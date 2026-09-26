package tachiyomi.domain.tsuzuki.content

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon
import tachiyomi.domain.tsuzuki.addon.repository.AddonSourceEligibility
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSearchProgress
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSourceOutcome
import tachiyomi.domain.tsuzuki.content.interactor.ContentOptionLookup
import tachiyomi.domain.tsuzuki.content.interactor.DiscoverReadableChapter
import tachiyomi.domain.tsuzuki.content.interactor.FastDiscoveryCompletion
import tachiyomi.domain.tsuzuki.content.interactor.FastDiscoveryFailureStage
import tachiyomi.domain.tsuzuki.content.interactor.FastReadingDiscoveryEvent
import tachiyomi.domain.tsuzuki.content.interactor.InitialDiscoveryBudget
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceCandidate
import tachiyomi.domain.tsuzuki.source.model.ScoredSourceCandidate
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverReadableChapterTest {

    @Test
    fun `a verified existing option avoids all cold discovery work`() = runTest {
        var installedCalls = 0
        var sourceCalls = 0
        val runner = DiscoverReadableChapter(
            lookupExisting = { _, _ -> lookup(option("already", "en")) },
            lookupAfterBinding = { _, _, _ -> lookup() },
            installedAddons = {
                installedCalls++
                listOf(installed("new", 7L))
            },
            sourceEligibility = { listOf(source(7L, "en")) },
            contentPreference = { null },
            globalLanguages = { emptyList() },
            deviceLocale = { Locale.US },
            sourceSearch = {
                sourceCalls++
                flow { }
            },
            refreshBinding = { Result.success(Unit) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val events = runner.discover("title", "chapter").toList()

        events.filterIsInstance<FastReadingDiscoveryEvent.Ready>().single().alreadyAvailable shouldBe true
        events.last() shouldBe FastReadingDiscoveryEvent.Completed(FastDiscoveryCompletion.FOUND, emptyMap())
        installedCalls shouldBe 0
        sourceCalls shouldBe 0
    }

    @Test
    fun `empty chapter triggers one bounded search and surfaces the first verified option`() = runTest {
        val addon = installed("english", 7L, 8L, 9L)
        val allowed = mutableListOf<Set<Long>?>()
        var refreshed = 0
        val runner = DiscoverReadableChapter(
            lookupExisting = { _, _ -> lookup() },
            lookupAfterBinding = { _, _, _ -> lookup(option("english", "en")) },
            installedAddons = { listOf(addon) },
            sourceEligibility = {
                listOf(source(7L, "en"), source(8L, "en"), source(9L, "en"))
            },
            contentPreference = { null },
            globalLanguages = { emptyList() },
            deviceLocale = { Locale.US },
            sourceSearch = { request ->
                allowed += request.allowedSourceIds
                flow {
                    emit(
                        ContentBindingSearchProgress.SourceCompleted(
                            sourceId = 7L,
                            language = "en",
                            outcome = ContentBindingSourceOutcome.BOUND,
                            bindings = listOf(binding("english", 7L)),
                        ),
                    )
                    emit(ContentBindingSearchProgress.Completed(listOf(7L), 2))
                }
            },
            refreshBinding = {
                refreshed++
                Result.success(Unit)
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val events = runner.discover("title", "chapter").toList()

        refreshed shouldBe 1
        allowed.single() shouldBe setOf(7L, 8L, 9L)
        events.filterIsInstance<FastReadingDiscoveryEvent.Ready>().single().alreadyAvailable shouldBe false
        events.last() shouldBe FastReadingDiscoveryEvent.Completed(
            FastDiscoveryCompletion.FOUND,
            mapOf(addon.id to setOf(7L)),
        )
    }

    @Test
    fun `an ambiguous candidate never creates chapter evidence implicitly`() = runTest {
        val addon = installed("english", 7L)
        var refreshes = 0
        val candidate = ScoredSourceCandidate(
            candidate = ReadingSourceCandidate(
                sourceId = 7L,
                sourceName = "English source",
                language = "en",
                sourceUrl = "/edition",
                title = "Death Note",
                thumbnailUrl = null,
                author = null,
                artist = null,
                description = null,
                genres = null,
                status = 0L,
            ),
            confidence = 0.81,
            sourcePreferenceRank = 0,
        )
        val runner = DiscoverReadableChapter(
            lookupExisting = { _, _ -> lookup() },
            lookupAfterBinding = { _, _, _ -> lookup() },
            installedAddons = { listOf(addon) },
            sourceEligibility = { listOf(source(7L, "en")) },
            contentPreference = { null },
            globalLanguages = { listOf("en") },
            deviceLocale = { Locale.US },
            sourceSearch = {
                flow {
                    emit(
                        ContentBindingSearchProgress.SourceCompleted(
                            sourceId = 7L,
                            language = "en",
                            outcome = ContentBindingSourceOutcome.CONFIRMATION_REQUIRED,
                            candidates = listOf(candidate),
                        ),
                    )
                    emit(ContentBindingSearchProgress.Completed(listOf(7L), 0))
                }
            },
            refreshBinding = {
                refreshes++
                Result.success(Unit)
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val events = runner.discover("title", "chapter").toList()

        events.filterIsInstance<FastReadingDiscoveryEvent.ConfirmationRequired>()
            .single().candidates shouldBe listOf(candidate)
        refreshes shouldBe 0
        events.filterIsInstance<FastReadingDiscoveryEvent.Ready>().size shouldBe 0
        events.last() shouldBe FastReadingDiscoveryEvent.Completed(
            FastDiscoveryCompletion.CONFIRMATION_REQUIRED,
            mapOf(addon.id to setOf(7L)),
        )
    }

    @Test
    fun `a failed peer does not suppress the other addons verified result`() = runTest {
        val pt = installed("portuguese", 7L)
        val en = installed("english", 8L)
        val runner = DiscoverReadableChapter(
            lookupExisting = { _, _ -> lookup() },
            lookupAfterBinding = { _, _, binding ->
                if (binding.addonId == en.id) lookup(option("english", "en")) else lookup()
            },
            installedAddons = { listOf(pt, en) },
            sourceEligibility = { addonId ->
                if (addonId == pt.id) listOf(source(7L, "pt-BR")) else listOf(source(8L, "en"))
            },
            contentPreference = { null },
            globalLanguages = { emptyList() },
            deviceLocale = { Locale.forLanguageTag("pt-BR") },
            sourceSearch = { request ->
                flow {
                    if (request.addonId == pt.id) {
                        emit(
                            ContentBindingSearchProgress.SourceCompleted(
                                sourceId = 7L,
                                language = "pt-BR",
                                outcome = ContentBindingSourceOutcome.FAILURE,
                            ),
                        )
                    } else {
                        emit(
                            ContentBindingSearchProgress.SourceCompleted(
                                sourceId = 8L,
                                language = "en",
                                outcome = ContentBindingSourceOutcome.BOUND,
                                bindings = listOf(binding("english", 8L)),
                            ),
                        )
                    }
                    emit(ContentBindingSearchProgress.Completed(request.allowedSourceIds.orEmpty().toList(), 0))
                }
            },
            refreshBinding = { Result.success(Unit) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val events = runner.discover("title", "chapter").toList()

        events.filterIsInstance<FastReadingDiscoveryEvent.SourceFailed>()
            .single().stage shouldBe FastDiscoveryFailureStage.SEARCH
        events.filterIsInstance<FastReadingDiscoveryEvent.Ready>().single()
            .options.map { it.language } shouldBe listOf("en")
        events.last().let { it as FastReadingDiscoveryEvent.Completed }.reason shouldBe
            FastDiscoveryCompletion.FOUND
    }

    @Test
    fun `healthy fast edition is emitted while earlier bound edition is still refreshing`() = runTest {
        val slow = installed("portuguese", 7L)
        val fast = installed("english", 8L)
        val slowStarted = CompletableDeferred<Unit>()
        val slowRelease = CompletableDeferred<Unit>()
        val runner = DiscoverReadableChapter(
            lookupExisting = { _, _ -> lookup() },
            lookupAfterBinding = { _, _, binding ->
                if (binding.addonId == fast.id) lookup(option("english", "en")) else lookup()
            },
            installedAddons = { listOf(slow, fast) },
            sourceEligibility = { id ->
                if (id == slow.id) listOf(source(7L, "pt-BR")) else listOf(source(8L, "en"))
            },
            contentPreference = { null },
            globalLanguages = { listOf("pt-BR", "en") },
            deviceLocale = { Locale.forLanguageTag("pt-BR") },
            sourceSearch = { request ->
                flow {
                    val sourceId = if (request.addonId == slow.id) 7L else 8L
                    emit(
                        ContentBindingSearchProgress.SourceCompleted(
                            sourceId = sourceId,
                            language = if (request.addonId == slow.id) "pt-BR" else "en",
                            outcome = ContentBindingSourceOutcome.BOUND,
                            bindings = listOf(binding(request.addonId.value, sourceId)),
                        ),
                    )
                    emit(ContentBindingSearchProgress.Completed(listOf(sourceId), 0))
                }
            },
            refreshBinding = { binding ->
                if (binding.addonId == slow.id) {
                    slowStarted.complete(Unit)
                    slowRelease.await()
                }
                Result.success(Unit)
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val received = mutableListOf<FastReadingDiscoveryEvent>()
        val job = launch {
            runner.discover("title", "chapter").collect(received::add)
        }

        runCurrent()
        slowStarted.isCompleted shouldBe true
        received.filterIsInstance<FastReadingDiscoveryEvent.Ready>()
            .single().options.map { it.addonId } shouldBe listOf(fast.id)

        slowRelease.complete(Unit)
        advanceUntilIdle()
        job.join()
        received.filterIsInstance<FastReadingDiscoveryEvent.Completed>()
            .single().reason shouldBe FastDiscoveryCompletion.FOUND
    }

    @Test
    fun `duplicate binding events do not trigger duplicate targeted inventories`() = runTest {
        val addon = installed("english", 7L)
        var refreshCalls = 0
        val runner = DiscoverReadableChapter(
            lookupExisting = { _, _ -> lookup() },
            lookupAfterBinding = { _, _, _ -> lookup() },
            installedAddons = { listOf(addon) },
            sourceEligibility = { listOf(source(7L, "en")) },
            contentPreference = { null },
            globalLanguages = { listOf("en") },
            deviceLocale = { Locale.US },
            sourceSearch = {
                flow {
                    repeat(2) {
                        emit(
                            ContentBindingSearchProgress.SourceCompleted(
                                sourceId = 7L,
                                language = "en",
                                outcome = ContentBindingSourceOutcome.BOUND,
                                bindings = listOf(binding("english", 7L)),
                            ),
                        )
                    }
                    emit(ContentBindingSearchProgress.Completed(listOf(7L), 0))
                }
            },
            refreshBinding = {
                refreshCalls++
                Result.success(Unit)
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        runner.discover("title", "chapter").toList()

        refreshCalls shouldBe 1
    }

    @Test
    fun `later enabled addon is searched automatically when the first two have no chapters`() = runTest {
        val missing = installed("animexnovel", 1L)
        val broken = installed("mangaflix", 2L)
        val readable = installed("mangalivreto", 3L)
        val queried = mutableListOf<AddonId>()
        val runner = DiscoverReadableChapter(
            lookupExisting = { _, _ -> lookup() },
            lookupAfterBinding = { _, _, binding ->
                if (binding.addonId == readable.id) lookup(option("mangalivreto", "pt-BR")) else lookup()
            },
            installedAddons = { listOf(readable, missing, broken) },
            sourceEligibility = { addonId ->
                when (addonId) {
                    missing.id -> listOf(source(1L, "pt-BR"))
                    broken.id -> listOf(source(2L, "pt-BR"))
                    else -> listOf(source(3L, "pt-BR"))
                }
            },
            contentPreference = { null },
            globalLanguages = { listOf("pt-BR", "en") },
            deviceLocale = { Locale.forLanguageTag("pt-BR") },
            sourceSearch = { request ->
                queried += request.addonId
                flow {
                    when (request.addonId) {
                        broken.id -> emit(
                            ContentBindingSearchProgress.SourceCompleted(
                                sourceId = 2L,
                                language = "pt-BR",
                                outcome = ContentBindingSourceOutcome.FAILURE,
                            ),
                        )
                        readable.id -> emit(
                            ContentBindingSearchProgress.SourceCompleted(
                                sourceId = 3L,
                                language = "pt-BR",
                                outcome = ContentBindingSourceOutcome.BOUND,
                                bindings = listOf(binding("mangalivreto", 3L)),
                            ),
                        )
                        else -> Unit
                    }
                    emit(ContentBindingSearchProgress.Completed(request.allowedSourceIds.orEmpty().toList(), 0))
                }
            },
            refreshBinding = { Result.success(Unit) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val events = runner.discover("title", "chapter").toList()

        queried.toSet() shouldBe setOf(missing.id, broken.id, readable.id)
        events.filterIsInstance<FastReadingDiscoveryEvent.Ready>()
            .single().options.single().addonId shouldBe readable.id
        (events.last() as FastReadingDiscoveryEvent.Completed).reason shouldBe
            FastDiscoveryCompletion.FOUND
    }

    @Test
    fun `two healthy editions appear independently without waiting for each other`() = runTest {
        val first = installed("mangaflix", 1L)
        val second = installed("mangalivreto", 2L)
        val slowRelease = CompletableDeferred<Unit>()
        val slowStarted = CompletableDeferred<Unit>()
        val runner = DiscoverReadableChapter(
            lookupExisting = { _, _ -> lookup() },
            lookupAfterBinding = { _, _, binding ->
                lookup(option(binding.addonId.value, "pt-BR").copy(key = binding.id))
            },
            installedAddons = { listOf(first, second) },
            sourceEligibility = { id ->
                listOf(source(if (id == first.id) 1L else 2L, "pt-BR"))
            },
            contentPreference = { null },
            globalLanguages = { listOf("pt-BR") },
            deviceLocale = { Locale.forLanguageTag("pt-BR") },
            sourceSearch = { request ->
                flow {
                    val id = if (request.addonId == first.id) 1L else 2L
                    emit(
                        ContentBindingSearchProgress.SourceCompleted(
                            sourceId = id,
                            language = "pt-BR",
                            outcome = ContentBindingSourceOutcome.BOUND,
                            bindings = listOf(binding(request.addonId.value, id)),
                        ),
                    )
                    emit(ContentBindingSearchProgress.Completed(listOf(id), 0))
                }
            },
            refreshBinding = { binding ->
                if (binding.addonId == first.id) {
                    slowStarted.complete(Unit)
                    slowRelease.await()
                }
                Result.success(Unit)
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val events = mutableListOf<FastReadingDiscoveryEvent>()
        val job = launch { runner.discover("title", "chapter").collect(events::add) }

        runCurrent()
        slowStarted.isCompleted shouldBe true
        events.filterIsInstance<FastReadingDiscoveryEvent.Ready>()
            .single().options.single().addonId shouldBe second.id
        slowRelease.complete(Unit)
        advanceUntilIdle()
        job.join()

        events.filterIsInstance<FastReadingDiscoveryEvent.Ready>()
            .flatMap { it.options }.map { it.addonId }.toSet() shouldBe setOf(first.id, second.id)
        (events.last() as FastReadingDiscoveryEvent.Completed).reason shouldBe
            FastDiscoveryCompletion.FOUND
    }

    @Test
    fun `initial discovery time budget exits a cooperatively slow source`() = runTest {
        val addon = installed("slow", 7L)
        val runner = DiscoverReadableChapter(
            lookupExisting = { _, _ -> lookup() },
            lookupAfterBinding = { _, _, _ -> lookup() },
            installedAddons = { listOf(addon) },
            sourceEligibility = { listOf(source(7L, "en")) },
            contentPreference = { null },
            globalLanguages = { listOf("en") },
            deviceLocale = { Locale.US },
            sourceSearch = {
                flow {
                    delay(20_000)
                    emit(ContentBindingSearchProgress.Completed(listOf(7L), 0))
                }
            },
            refreshBinding = { Result.success(Unit) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val events = runner.discover(
            canonicalTitleId = "title",
            canonicalChapterId = "chapter",
            budget = InitialDiscoveryBudget(totalMillis = 150, existingLookupMillis = 20),
        ).toList()

        events.last().let { it as FastReadingDiscoveryEvent.Completed }.reason shouldBe
            FastDiscoveryCompletion.TIME_BUDGET
    }

    private fun lookup(vararg options: ContentOption) =
        ContentOptionLookup(options.toList(), emptyList(), 1)

    private fun option(addon: String, language: String) = ContentOption(
        key = addon + ":" + language,
        canonicalChapterId = "chapter",
        addonId = AddonId(addon),
        language = language,
        scanlationGroup = null,
        releaseDate = null,
        delivery = ContentDelivery.Mihon(sourceId = 7L, mangaId = 1L, chapterId = 2L),
    )

    private fun installed(name: String, vararg ids: Long) = InstalledAddon(
        id = AddonId(name),
        displayName = name,
        enabled = true,
        versionName = "1.0",
        mihonSourceIds = ids.toList(),
        hasSettings = false,
    )

    private fun binding(name: String, sourceId: Long) = ContentBinding(
        id = name,
        canonicalTitleId = "title",
        addonId = AddonId(name),
        providerTitleKey = sourceId.toString() + ":title",
        matchConfidence = 0.99,
        verifiedByUser = false,
        availability = ContentBindingAvailability.AVAILABLE,
        runtimePayload = byteArrayOf(),
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun source(id: Long, language: String) =
        AddonSourceEligibility(id, language, enabled = true)
}
