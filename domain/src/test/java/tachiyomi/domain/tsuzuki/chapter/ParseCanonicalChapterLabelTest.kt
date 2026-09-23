package tachiyomi.domain.tsuzuki.chapter

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterIdentity
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant

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
        chapter.confidence shouldBe 1.0
        abbreviated.confidence shouldBe 1.0
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
    fun `zero placeholder keeps semantic one shot distinct from regular zero chapter`() {
        val oneShot = parse.execute("Ch. 0 - Oneshot")
        val regularZero = parse.execute("Ch. 0 - Volume 20")

        oneShot.type shouldBe CanonicalChapterType.ONESHOT
        oneShot.baseNumber.shouldBeNull()
        regularZero.type shouldBe CanonicalChapterType.REGULAR
        regularZero.baseNumber shouldBe 0
        (oneShot.identity == regularZero.identity) shouldBe false
        (regularZero.identity < oneShot.identity) shouldBe true
    }

    @Test
    fun `numbered prologues and epilogues preserve distinct identities`() {
        val prologue1 = parse.execute("Prologue 1")
        val prologue2 = parse.execute("Prologue 2")
        val epilogue1 = parse.execute("Epilogue 1")
        val epilogue2 = parse.execute("Epilogue 2")

        prologue1.type shouldBe CanonicalChapterType.PROLOGUE
        prologue1.baseNumber shouldBe 1
        prologue2.baseNumber shouldBe 2
        (prologue1.identity == prologue2.identity) shouldBe false

        epilogue1.type shouldBe CanonicalChapterType.EPILOGUE
        epilogue1.baseNumber shouldBe 1
        epilogue2.baseNumber shouldBe 2
        (epilogue1.identity == epilogue2.identity) shouldBe false
    }

    @Test
    fun `semantic parsing preserves numbered one shots and rejects ambiguous numeric tails`() {
        val numberedOneShot = parse.execute("One-shot 2")

        numberedOneShot.type shouldBe CanonicalChapterType.ONESHOT
        numberedOneShot.baseNumber shouldBe 2
        parse.execute("Extra 3 4").type shouldBe CanonicalChapterType.UNKNOWN
        parse.execute("Prologue 1 2").type shouldBe CanonicalChapterType.UNKNOWN
        parse.execute("24 Part 2 3").type shouldBe CanonicalChapterType.UNKNOWN
        parse.execute("One-shot 2 3").type shouldBe CanonicalChapterType.UNKNOWN
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
    fun `volume prefixed chapter labels retain explicit chapter identity`() {
        val first = parse.execute("Vol. 1 Ch. 1", numericHint = 1.0)
        val transition = parse.execute("Volume 12 - Chapter 137", numericHint = 137.0)
        val fractional = parse.execute("Vol. 12 Ch. 137.5", numericHint = 137.5)

        first.type shouldBe CanonicalChapterType.REGULAR
        first.baseNumber shouldBe 1
        first.confidence shouldBe 1.0
        transition.baseNumber shouldBe 137
        transition.part.shouldBeNull()
        transition.confidence shouldBe 1.0
        fractional.baseNumber shouldBe 137
        fractional.part shouldBe 5
        parse.execute("Vol. 12 Ch. 137 138", numericHint = 137.0)
            .type shouldBe CanonicalChapterType.UNKNOWN
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
    fun `numeric hint disambiguates compact volume chapter labels without fabricating structure`() {
        val compact = parse.execute("9.46", numericHint = 46.0)
        val genuineDecimal = parse.execute("9.46", numericHint = 9.46)
        val shortDecimal = parse.execute("12.5", numericHint = 5.0)

        compact.type shouldBe CanonicalChapterType.REGULAR
        compact.baseNumber shouldBe 46
        compact.part.shouldBeNull()
        compact.displayNumber shouldBe "46"
        compact.confidence shouldBe 0.95

        genuineDecimal.baseNumber shouldBe 9
        genuineDecimal.part shouldBe 46
        genuineDecimal.displayNumber shouldBe "9.46"
        genuineDecimal.confidence shouldBe 0.80

        shortDecimal.baseNumber shouldBe 12
        shortDecimal.part shouldBe 5
        shortDecimal.displayNumber shouldBe "12.5"
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
    fun `canonical chapter derives ordering from its identity`() {
        val chapter = CanonicalChapter(
            id = "chapter-12a",
            canonicalTitleId = "title-1",
            displayNumber = "12a",
            type = CanonicalChapterType.REGULAR,
            baseNumber = 12,
            alphaSuffix = "A",
        )

        chapter.sortKey shouldBe chapter.identity.sortKey
    }

    @Test
    fun `chapter variant preserves structured raw source metadata losslessly`() {
        val metadata = JsonObject(
            mapOf(
                "flag" to JsonPrimitive(true),
                "count" to JsonPrimitive(2),
                "nested" to JsonObject(
                    mapOf(
                        "values" to JsonArray(
                            listOf(JsonPrimitive("alpha"), JsonPrimitive(3)),
                        ),
                    ),
                ),
            ),
        )
        val variant = ChapterVariant(
            id = "variant-1",
            canonicalChapterId = "chapter-1",
            rawSourceMetadata = metadata,
        )

        variant.rawSourceMetadata shouldBe metadata
    }

    @Test
    fun `whole zero chapter sorts before zero point five and following chapters`() {
        val chapters = listOf("0.5", "1", "0", "1.5")
            .map(parse::execute)

        chapters.sortedBy { it.identity }
            .map { it.displayNumber } shouldBe listOf("0", "0.5", "1", "1.5")
        chapters.sortedBy { it.sortKey }
            .map { it.displayNumber } shouldBe listOf("0", "0.5", "1", "1.5")
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
