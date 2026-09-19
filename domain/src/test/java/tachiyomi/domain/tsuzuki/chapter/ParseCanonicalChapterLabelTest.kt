package tachiyomi.domain.tsuzuki.chapter

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterIdentity
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType

class ParseCanonicalChapterLabelTest {

    private val parse = ParseCanonicalChapterLabel()

    @Test
    fun `numeric labels are regular chapters without floating point identity`() {
        val chapter = parse.execute("Chapter 12")
        val abbreviated = parse.execute("Ch. 012")

        chapter.type shouldBe CanonicalChapterType.REGULAR
        chapter.baseNumber shouldBe 12
        chapter.part.shouldBeNull()
        chapter.alphaSuffix.shouldBeNull()
        chapter.displayNumber shouldBe "12"
        abbreviated.identity shouldBe chapter.identity
        chapter.identity.shouldBeInstanceOf<CanonicalChapterIdentity>()
    }

    @Test
    fun `decimal label preserves part as a structured component`() {
        val chapter = parse.execute("12.5")

        chapter.type shouldBe CanonicalChapterType.REGULAR
        chapter.baseNumber shouldBe 12
        chapter.part shouldBe 5
        chapter.alphaSuffix.shouldBeNull()
        chapter.identity shouldBe CanonicalChapterIdentity(
            type = CanonicalChapterType.REGULAR,
            baseNumber = 12,
            part = 5,
            alphaSuffix = null,
        )
    }

    @Test
    fun `alpha suffix remains distinct from regular and decimal chapters`() {
        val chapter = parse.execute("12a")
        val regular = parse.execute("12")
        val decimal = parse.execute("12.5")

        chapter.type shouldBe CanonicalChapterType.REGULAR
        chapter.baseNumber shouldBe 12
        chapter.part.shouldBeNull()
        chapter.alphaSuffix shouldBe "a"
        chapter.identity shouldBe CanonicalChapterIdentity(
            type = CanonicalChapterType.REGULAR,
            baseNumber = 12,
            part = null,
            alphaSuffix = "a",
        )
        (chapter.identity == regular.identity) shouldBe false
        (chapter.identity == decimal.identity) shouldBe false
    }

    @Test
    fun `part wording is parsed without collapsing into the chapter number`() {
        val chapter = parse.execute("24 Part 2")

        chapter.type shouldBe CanonicalChapterType.REGULAR
        chapter.baseNumber shouldBe 24
        chapter.part shouldBe 2
        chapter.displayNumber shouldBe "24 Part 2"
    }

    @Test
    fun `special chapter labels get semantic types`() {
        parse.execute("Extra 3").let {
            it.type shouldBe CanonicalChapterType.EXTRA
            it.baseNumber shouldBe 3
        }
        parse.execute("Special 2").let {
            it.type shouldBe CanonicalChapterType.SPECIAL
            it.baseNumber shouldBe 2
        }
        parse.execute("Prologue").let {
            it.type shouldBe CanonicalChapterType.PROLOGUE
            it.baseNumber.shouldBeNull()
        }
        parse.execute("Epilogue").type shouldBe CanonicalChapterType.EPILOGUE
        parse.execute("One-shot").type shouldBe CanonicalChapterType.ONESHOT
    }

    @Test
    fun `common Portuguese labels get semantic types`() {
        parse.execute("Capítulo 12").let {
            it.type shouldBe CanonicalChapterType.REGULAR
            it.baseNumber shouldBe 12
        }
        parse.execute("Prólogo").type shouldBe CanonicalChapterType.PROLOGUE
        parse.execute("Epílogo").type shouldBe CanonicalChapterType.EPILOGUE
        parse.execute("Especial 2").let {
            it.type shouldBe CanonicalChapterType.SPECIAL
            it.baseNumber shouldBe 2
        }
    }

    @Test
    fun `unknown labels do not fabricate structure from numeric hints`() {
        val unknown = parse.execute("Bonus chapter", numericHint = 12.0)
        val blankWithHint = parse.execute("", numericHint = 12.0)

        unknown.type shouldBe CanonicalChapterType.UNKNOWN
        unknown.baseNumber.shouldBeNull()
        unknown.part.shouldBeNull()
        unknown.alphaSuffix.shouldBeNull()
        blankWithHint.type shouldBe CanonicalChapterType.UNKNOWN
        blankWithHint.baseNumber.shouldBeNull()
    }

    @Test
    fun `numeric hint is auxiliary and does not override an explicit label`() {
        val chapter = parse.execute("Chapter 12", numericHint = 99.0)

        chapter.type shouldBe CanonicalChapterType.REGULAR
        chapter.baseNumber shouldBe 12
        chapter.numericHint shouldBe "99"
    }

    @Test
    fun `raw label and display representation remain separate`() {
        val chapter = parse.execute("  Ch. 012 - The Beginning  ")

        chapter.rawLabel shouldBe "  Ch. 012 - The Beginning  "
        chapter.displayNumber shouldBe "12"
        chapter.baseNumber shouldBe 12
    }

    @Test
    fun `identity keeps chapter type and components distinct`() {
        val regular = parse.execute("3").identity
        val extra = parse.execute("Extra 3").identity
        val suffix = parse.execute("12a").identity
        val decimal = parse.execute("12.5").identity

        (regular == extra) shouldBe false
        (suffix == parse.execute("12").identity) shouldBe false
        (decimal == parse.execute("12").identity) shouldBe false
    }

    @Test
    fun `identity normalizes alpha suffixes at its boundary`() {
        val uppercase = CanonicalChapterIdentity(
            type = CanonicalChapterType.REGULAR,
            baseNumber = 12,
            alphaSuffix = "A",
        )
        val lowercase = CanonicalChapterIdentity(
            type = CanonicalChapterType.REGULAR,
            baseNumber = 12,
            alphaSuffix = "a",
        )

        uppercase shouldBe lowercase
        uppercase.sortKey shouldBe lowercase.sortKey
        uppercase.compareTo(lowercase) shouldBe 0
    }

    @Test
    fun `sort keys are deterministic and naturally ordered`() {
        val labels = listOf("10", "2", "12.5", "12a", "12", "Prologue", "Extra 3")
        val first = labels.map(parse::execute).sortedBy { it.sortKey }
        val second = labels.reversed().map(parse::execute).sortedBy { it.sortKey }

        first.map { it.rawLabel } shouldBe second.map { it.rawLabel }
        first.map { it.sortKey } shouldBe first.map { it.sortKey }.sorted()
    }
}
