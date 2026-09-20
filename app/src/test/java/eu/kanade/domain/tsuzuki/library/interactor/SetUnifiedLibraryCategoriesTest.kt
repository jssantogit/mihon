package eu.kanade.domain.tsuzuki.library.interactor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SetUnifiedLibraryCategoriesTest {

    @Test
    fun `category change projects to every materialized mihon representation then canonical state`() = runTest {
        val calls = mutableListOf<String>()
        val interactor = SetUnifiedLibraryCategories(
            getMihonMangaIds = { listOf(10L, 20L) },
            getMihonCategoryIds = { id ->
                calls += "snapshot:$id"
                listOf(id)
            },
            setMihonCategories = { id, categories ->
                calls += "mihon:$id:${categories.joinToString()}"
            },
            setCanonicalCategories = { titleId, categories ->
                calls += "canonical:$titleId:${categories.joinToString()}"
            },
        )

        interactor.execute("canonical-1", listOf(3L, 4L, 3L))

        calls shouldContainExactly listOf(
            "snapshot:10",
            "snapshot:20",
            "mihon:10:3, 4",
            "mihon:20:3, 4",
            "canonical:canonical-1:3, 4",
        )
    }

    @Test
    fun `projection failure restores earlier mihon category assignments`() = runTest {
        val writes = mutableListOf<String>()
        var canonicalCalled = false
        val interactor = SetUnifiedLibraryCategories(
            getMihonMangaIds = { listOf(10L, 20L) },
            getMihonCategoryIds = { id -> listOf(id + 100L) },
            setMihonCategories = { id, categories ->
                writes += "$id:${categories.joinToString()}"
                if (id == 20L && categories == listOf(3L)) {
                    error("projection failure")
                }
            },
            setCanonicalCategories = { _, _ ->
                canonicalCalled = true
            },
        )

        shouldThrow<IllegalStateException> {
            interactor.execute("canonical-1", listOf(3L))
        }

        writes shouldContainExactly listOf(
            "10:3",
            "20:3",
            "10:110",
        )
        canonicalCalled shouldBe false
    }

    @Test
    fun `canonical failure restores all mihon category assignments in reverse order`() = runTest {
        val writes = mutableListOf<String>()
        val interactor = SetUnifiedLibraryCategories(
            getMihonMangaIds = { listOf(10L, 20L) },
            getMihonCategoryIds = { id -> listOf(id + 100L) },
            setMihonCategories = { id, categories ->
                writes += "$id:${categories.joinToString()}"
            },
            setCanonicalCategories = { _, _ ->
                error("canonical failure")
            },
        )

        shouldThrow<IllegalStateException> {
            interactor.execute("canonical-1", listOf(3L))
        }

        writes shouldContainExactly listOf(
            "10:3",
            "20:3",
            "20:120",
            "10:110",
        )
    }

    @Test
    fun `title without materialized mihon representations updates canonical state directly`() = runTest {
        val calls = mutableListOf<String>()
        val interactor = SetUnifiedLibraryCategories(
            getMihonMangaIds = { emptyList() },
            getMihonCategoryIds = { error("must not snapshot") },
            setMihonCategories = { _, _ -> error("must not project") },
            setCanonicalCategories = { titleId, categories ->
                calls += "$titleId:${categories.joinToString()}"
            },
        )

        interactor.execute("canonical-1", listOf(7L))

        calls shouldContainExactly listOf("canonical-1:7")
    }
}
