package eu.kanade.presentation.manga.components

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.MangaCover

class MangaCoverFallbackTest {

    @Test
    fun `cover candidates keep ordered unique fallbacks`() {
        val sourceCover = MangaCover(
            mangaId = 7L,
            sourceId = 9L,
            isMangaFavorite = false,
            url = "https://source.example/cover.jpg",
            lastModified = 0L,
        )

        coverCandidates(
            primary = "https://provider.example/cover.jpg",
            fallbacks = listOf(
                sourceCover,
                "https://source.example/cover.jpg",
                sourceCover,
                null,
            ),
        ) shouldBe listOf(
            "https://provider.example/cover.jpg",
            sourceCover,
            "https://source.example/cover.jpg",
        )
    }

    @Test
    fun `cover candidates drop null primary and preserve first usable fallback`() {
        coverCandidates(
            primary = null,
            fallbacks = listOf(null, "file://cover.jpg"),
        ) shouldBe listOf("file://cover.jpg")
    }
}
