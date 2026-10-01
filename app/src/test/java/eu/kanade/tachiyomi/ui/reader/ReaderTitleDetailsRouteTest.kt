package eu.kanade.tachiyomi.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReaderTitleDetailsRouteTest {

    @Test
    fun `canonical identity always wins over legacy manga id`() {
        resolveReaderTitleDetailsRoute(
            canonicalTitleId = "canonical-123",
            mangaId = 42L,
        ) shouldBe ReaderTitleDetailsRoute.Canonical("canonical-123")
    }

    @Test
    fun `legacy manga id remains a compatibility fallback`() {
        resolveReaderTitleDetailsRoute(
            canonicalTitleId = null,
            mangaId = 42L,
        ) shouldBe ReaderTitleDetailsRoute.Legacy(42L)
    }

    @Test
    fun `invalid identities expose no details route`() {
        resolveReaderTitleDetailsRoute(
            canonicalTitleId = "",
            mangaId = -1L,
        ) shouldBe ReaderTitleDetailsRoute.Unavailable
    }
}
