package tachiyomi.domain.tsuzuki.chapter.interactor

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ParseCanonicalChapterVolumeTest {

    private val parse = ParseCanonicalChapterVolume()

    @Test
    fun `explicit volume chapter labels return the numeric volume`() {
        parse.execute("Vol.1 Ch.1") shouldBe 1
        parse.execute("Vol. 12 - Chapter 137") shouldBe 12
        parse.execute("Volume 3: Ch. 37.5") shouldBe 3
    }

    @Test
    fun `volume is unknown when the label omits a numeric volume`() {
        parse.execute("Chapter 37").shouldBeNull()
        parse.execute("Vol.none Ch. 37").shouldBeNull()
        parse.execute("Vol. 3 Extra").shouldBeNull()
    }

    @Test
    fun `ambiguous or out of range volume labels are not guessed`() {
        parse.execute("Vol. 1 Ch. 37 Vol. 2 Ch. 37").shouldBeNull()
        parse.execute("Vol. 1-2 Ch. 37").shouldBeNull()
        parse.execute("Vol. 999999999999999999 Ch. 37").shouldBeNull()
    }
}
