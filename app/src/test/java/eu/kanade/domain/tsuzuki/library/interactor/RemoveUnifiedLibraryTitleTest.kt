package eu.kanade.domain.tsuzuki.library.interactor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class RemoveUnifiedLibraryTitleTest {

    @Test
    fun `removing title clears every favorite mihon projection before canonical membership`() = runTest {
        val calls = mutableListOf<String>()
        val interactor = RemoveUnifiedLibraryTitle(
            getFavoriteMihonMangaIds = { listOf(10L, 20L) },
            setMihonFavorite = { id, favorite ->
                calls += "mihon:$id:$favorite"
                true
            },
            removeCanonical = {
                calls += "canonical:$it"
            },
        )

        interactor.execute("canonical-1") shouldBe true

        calls shouldContainExactly listOf(
            "mihon:10:false",
            "mihon:20:false",
            "canonical:canonical-1",
        )
    }

    @Test
    fun `projection failure rolls back earlier projections and keeps canonical membership`() = runTest {
        val calls = mutableListOf<String>()
        var canonicalRemoved = false
        val interactor = RemoveUnifiedLibraryTitle(
            getFavoriteMihonMangaIds = { listOf(10L, 20L) },
            setMihonFavorite = { id, favorite ->
                calls += "mihon:$id:$favorite"
                !(id == 20L && !favorite)
            },
            removeCanonical = {
                canonicalRemoved = true
            },
        )

        interactor.execute("canonical-1") shouldBe false

        calls shouldContainExactly listOf(
            "mihon:10:false",
            "mihon:20:false",
            "mihon:10:true",
        )
        canonicalRemoved shouldBe false
    }

    @Test
    fun `canonical failure restores all cleared projections`() = runTest {
        val calls = mutableListOf<String>()
        val interactor = RemoveUnifiedLibraryTitle(
            getFavoriteMihonMangaIds = { listOf(10L, 20L) },
            setMihonFavorite = { id, favorite ->
                calls += "mihon:$id:$favorite"
                true
            },
            removeCanonical = {
                error("canonical failure")
            },
        )

        shouldThrow<IllegalStateException> {
            interactor.execute("canonical-1")
        }

        calls shouldContainExactly listOf(
            "mihon:10:false",
            "mihon:20:false",
            "mihon:20:true",
            "mihon:10:true",
        )
    }

    @Test
    fun `title without favorite mihon projections removes canonical membership directly`() = runTest {
        var removed: String? = null
        val interactor = RemoveUnifiedLibraryTitle(
            getFavoriteMihonMangaIds = { emptyList() },
            setMihonFavorite = { _, _ -> error("must not project") },
            removeCanonical = { removed = it },
        )

        interactor.execute("canonical-1") shouldBe true

        removed shouldBe "canonical-1"
    }
}
