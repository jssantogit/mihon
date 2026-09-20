package eu.kanade.domain.tsuzuki.library.interactor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.library.model.SourceLibraryRepresentation

class SetUnifiedLibraryMembershipTest {

    @Test
    fun `add updates mihon projection before canonical membership`() = runTest {
        val calls = mutableListOf<String>()
        val interactor = SetUnifiedLibraryMembership(
            setMihonFavorite = { _, favorite ->
                calls += "mihon:$favorite"
                true
            },
            addCanonical = {
                calls += "canonical:add"
            },
            removeCanonical = { _, _ ->
                calls += "canonical:remove"
            },
        )

        interactor.set(source(), inLibrary = true) shouldBe true

        calls shouldContainExactly listOf("mihon:true", "canonical:add")
    }

    @Test
    fun `failed mihon projection does not touch canonical membership`() = runTest {
        var canonicalCalled = false
        val interactor = SetUnifiedLibraryMembership(
            setMihonFavorite = { _, _ -> false },
            addCanonical = { canonicalCalled = true },
            removeCanonical = { _, _ -> canonicalCalled = true },
        )

        interactor.set(source(), inLibrary = true) shouldBe false

        canonicalCalled shouldBe false
    }

    @Test
    fun `canonical failure rolls mihon projection back`() = runTest {
        val favoriteWrites = mutableListOf<Boolean>()
        val interactor = SetUnifiedLibraryMembership(
            setMihonFavorite = { _, favorite ->
                favoriteWrites += favorite
                true
            },
            addCanonical = {
                error("canonical failure")
            },
            removeCanonical = { _, _ -> },
        )

        shouldThrow<IllegalStateException> {
            interactor.set(source(), inLibrary = true)
        }

        favoriteWrites shouldContainExactly listOf(true, false)
    }

    @Test
    fun `remove updates canonical membership after mihon projection`() = runTest {
        val calls = mutableListOf<String>()
        val interactor = SetUnifiedLibraryMembership(
            setMihonFavorite = { _, favorite ->
                calls += "mihon:$favorite"
                true
            },
            addCanonical = {
                calls += "canonical:add"
            },
            removeCanonical = { sourceId, sourceUrl ->
                calls += "canonical:remove:$sourceId:$sourceUrl"
            },
        )

        interactor.set(source(), inLibrary = false) shouldBe true

        calls shouldContainExactly listOf("mihon:false", "canonical:remove:7:/title")
    }

    private fun source() = SourceLibraryRepresentation(
        mihonMangaId = 42L,
        sourceId = 7L,
        sourceUrl = "/title",
        language = "en",
        sourceAvailable = true,
        displayTitle = "Title",
        dateAdded = 100L,
        hasStarted = false,
    )
}
