package eu.kanade.tachiyomi.ui.library

import eu.kanade.tachiyomi.ui.tsuzuki.detail.CanonicalTitleScreen
import eu.kanade.tachiyomi.ui.tsuzuki.library.CanonicalLibraryCardModel
import eu.kanade.tachiyomi.ui.tsuzuki.library.canonicalLibraryDestination
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TsuzukiLibraryContractTest {

    @Test
    fun `library card contract is canonical and source neutral`() {
        val properties = CanonicalLibraryCardModel::class.java.declaredFields
            .map { it.name }

        properties shouldContainAll listOf(
            "canonicalTitleId",
            "title",
            "status",
            "categories",
            "readingState",
        )
        properties shouldNotContain "identityState"
        properties shouldNotContain "sourceId"
        properties shouldNotContain "language"
        properties shouldNotContain "sourceOrder"
        properties shouldNotContain "readingSource"
        properties shouldNotContain "sourceRepresentations"
    }

    @Test
    fun `library item click opens canonical title detail`() {
        canonicalLibraryDestination("canonical-1") shouldBe
            CanonicalTitleScreen("canonical-1")
    }
}
