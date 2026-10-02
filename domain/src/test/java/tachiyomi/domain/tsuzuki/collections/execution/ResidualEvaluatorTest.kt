package tachiyomi.domain.tsuzuki.collections.execution

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.catalog.model.CatalogScore
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

class ResidualEvaluatorTest {

    @Test
    fun `truth values implement three valued boolean semantics`() {
        TruthValue.TRUE.not() shouldBe TruthValue.FALSE
        TruthValue.FALSE.not() shouldBe TruthValue.TRUE
        TruthValue.UNKNOWN.not() shouldBe TruthValue.UNKNOWN

        TruthValue.all(listOf(TruthValue.TRUE, TruthValue.TRUE)) shouldBe TruthValue.TRUE
        TruthValue.all(listOf(TruthValue.TRUE, TruthValue.UNKNOWN)) shouldBe TruthValue.UNKNOWN
        TruthValue.all(listOf(TruthValue.UNKNOWN, TruthValue.FALSE)) shouldBe TruthValue.FALSE

        TruthValue.any(listOf(TruthValue.FALSE, TruthValue.FALSE)) shouldBe TruthValue.FALSE
        TruthValue.any(listOf(TruthValue.FALSE, TruthValue.UNKNOWN)) shouldBe TruthValue.UNKNOWN
        TruthValue.any(listOf(TruthValue.UNKNOWN, TruthValue.TRUE)) shouldBe TruthValue.TRUE
    }

    @Test
    fun `null numeric field evaluates to unknown`() {
        val expression = predicate(
            QueryField.CHAPTER_COUNT,
            QueryOperator.GREATER_THAN,
            QueryValue.of(100),
        )

        evaluate(expression, item(chapterCount = null)) shouldBe TruthValue.UNKNOWN
    }

    @Test
    fun `not of missing numeric field stays unknown`() {
        val expression = QueryExpression.Not(
            predicate(
                QueryField.CHAPTER_COUNT,
                QueryOperator.GREATER_THAN,
                QueryValue.of(100),
            ),
        )

        evaluate(expression, item(chapterCount = null)) shouldBe TruthValue.UNKNOWN
    }

    @Test
    fun `unsupported field is explicit rather than false or unknown`() {
        val expression = predicate(
            QueryField.AUTHOR,
            QueryOperator.EQUALS,
            QueryValue.of("Kentaro Miura"),
        )

        ResidualEvaluator.support(expression).shouldBeInstanceOf<ResidualSupport.Unsupported>()
        ResidualEvaluator.evaluate(expression, item()).shouldBeInstanceOf<ResidualEvaluation.Unsupported>()
    }

    @Test
    fun `numeric comparisons accept integer and double operands`() {
        val target = item(chapterCount = 120)

        evaluate(
            predicate(QueryField.CHAPTER_COUNT, QueryOperator.GREATER_OR_EQUAL, QueryValue.of(120.0)),
            target,
        ) shouldBe TruthValue.TRUE

        evaluate(
            predicate(QueryField.CHAPTER_COUNT, QueryOperator.LESS_THAN, QueryValue.of(121)),
            target,
        ) shouldBe TruthValue.TRUE
    }

    @Test
    fun `between is inclusive`() {
        val expression = predicate(
            QueryField.SCORE,
            QueryOperator.BETWEEN,
            QueryValue.range(QueryValue.of(80.0), QueryValue.of(90.0)),
        )

        evaluate(expression, item(score = 80.0)) shouldBe TruthValue.TRUE
        evaluate(expression, item(score = 90.0)) shouldBe TruthValue.TRUE
        evaluate(expression, item(score = 91.0)) shouldBe TruthValue.FALSE
    }

    @Test
    fun `genre collection membership is case insensitive and deterministic`() {
        val target = item(genres = listOf("Action", "Dark Fantasy"))

        evaluate(
            predicate(QueryField.GENRE, QueryOperator.EQUALS, QueryValue.of("action")),
            target,
        ) shouldBe TruthValue.TRUE

        evaluate(
            predicate(
                QueryField.GENRE,
                QueryOperator.IN,
                QueryValue.of(listOf(QueryValue.of("Comedy"), QueryValue.of("DARK FANTASY"))),
            ),
            target,
        ) shouldBe TruthValue.TRUE

        evaluate(
            predicate(QueryField.GENRE, QueryOperator.NOT_EQUALS, QueryValue.of("Romance")),
            target,
        ) shouldBe TruthValue.TRUE
    }

    @Test
    fun `empty genres are unknown because CatalogItem cannot distinguish absent metadata`() {
        val expression = predicate(
            QueryField.GENRE,
            QueryOperator.EQUALS,
            QueryValue.of("Action"),
        )

        evaluate(expression, item(genres = emptyList())) shouldBe TruthValue.UNKNOWN
    }

    @Test
    fun `status and work type use canonical enum names case insensitively`() {
        val target = item(
            status = CatalogItemStatus.ONGOING,
            format = CatalogItemFormat.MANHWA,
        )

        evaluate(
            predicate(QueryField.STATUS, QueryOperator.EQUALS, QueryValue.of("ongoing")),
            target,
        ) shouldBe TruthValue.TRUE

        evaluate(
            predicate(
                QueryField.WORK_TYPE,
                QueryOperator.IN,
                QueryValue.of(listOf(QueryValue.of("manga"), QueryValue.of("manhwa"))),
            ),
            target,
        ) shouldBe TruthValue.TRUE
    }

    @Test
    fun `mixed boolean tree preserves unknown semantics`() {
        val expression = QueryExpression.All(
            predicate(QueryField.STATUS, QueryOperator.EQUALS, QueryValue.of("ongoing")),
            QueryExpression.Any(
                predicate(QueryField.CHAPTER_COUNT, QueryOperator.GREATER_THAN, QueryValue.of(100)),
                predicate(QueryField.SCORE, QueryOperator.GREATER_OR_EQUAL, QueryValue.of(90.0)),
            ),
        )

        evaluate(
            expression,
            item(status = CatalogItemStatus.ONGOING, chapterCount = null, score = 95.0),
        ) shouldBe TruthValue.TRUE

        evaluate(
            expression,
            item(status = CatalogItemStatus.ONGOING, chapterCount = null, score = 70.0),
        ) shouldBe TruthValue.UNKNOWN
    }

    @Test
    fun `verified dates expose start year release year and inclusive date ranges`() {
        val target = item(
            startDate = "2021-04-09",
            endDate = "2024-01-15",
        )

        evaluate(
            predicate(QueryField.START_YEAR, QueryOperator.EQUALS, QueryValue.of(2021)),
            target,
        ) shouldBe TruthValue.TRUE
        evaluate(
            predicate(QueryField.RELEASE_YEAR, QueryOperator.GREATER_OR_EQUAL, QueryValue.of(2020)),
            target,
        ) shouldBe TruthValue.TRUE
        evaluate(
            predicate(
                QueryField.START_DATE,
                QueryOperator.BETWEEN,
                QueryValue.range(QueryValue.of("2021-01-01"), QueryValue.of("2021-12-31")),
            ),
            target,
        ) shouldBe TruthValue.TRUE
        evaluate(
            predicate(QueryField.END_DATE, QueryOperator.LESS_THAN, QueryValue.of("2025-01-01")),
            target,
        ) shouldBe TruthValue.TRUE
    }

    @Test
    fun `verified people publishing and taxonomy collections are residual evaluable`() {
        val target = item(
            authors = listOf("ONE"),
            artists = listOf("Yusuke Murata"),
            publishers = listOf("Shueisha"),
            magazines = listOf("Weekly Shonen Jump"),
            categories = listOf("Award Winning"),
            demographics = listOf("Shounen"),
        )

        evaluate(predicate(QueryField.AUTHOR, QueryOperator.EQUALS, QueryValue.of("one")), target) shouldBe
            TruthValue.TRUE
        evaluate(predicate(QueryField.ARTIST, QueryOperator.CONTAINS, QueryValue.of("YUSUKE MURATA")), target) shouldBe
            TruthValue.TRUE
        evaluate(predicate(QueryField.PUBLISHER, QueryOperator.EQUALS, QueryValue.of("shueisha")), target) shouldBe
            TruthValue.TRUE
        evaluate(
            predicate(QueryField.MAGAZINE, QueryOperator.EQUALS, QueryValue.of("weekly shonen jump")),
            target,
        ) shouldBe
            TruthValue.TRUE
        evaluate(predicate(QueryField.CATEGORY, QueryOperator.EQUALS, QueryValue.of("award winning")), target) shouldBe
            TruthValue.TRUE
        evaluate(predicate(QueryField.DEMOGRAPHIC, QueryOperator.EQUALS, QueryValue.of("shounen")), target) shouldBe
            TruthValue.TRUE
    }

    @Test
    fun `country popularity favorites and rank preserve missing as unknown`() {
        val target = item(
            country = "JP",
            popularity = 120_000,
            favorites = 8_000,
            rank = 42.5,
        )

        evaluate(predicate(QueryField.COUNTRY, QueryOperator.EQUALS, QueryValue.of("jp")), target) shouldBe
            TruthValue.TRUE
        evaluate(predicate(QueryField.POPULARITY, QueryOperator.GREATER_THAN, QueryValue.of(100_000)), target) shouldBe
            TruthValue.TRUE
        evaluate(
            predicate(
                QueryField.FAVORITES,
                QueryOperator.BETWEEN,
                QueryValue.range(QueryValue.of(1), QueryValue.of(10_000)),
            ),
            target,
        ) shouldBe
            TruthValue.TRUE
        evaluate(predicate(QueryField.RANK, QueryOperator.LESS_OR_EQUAL, QueryValue.of(50.0)), target) shouldBe
            TruthValue.TRUE

        evaluate(
            predicate(QueryField.POPULARITY, QueryOperator.GREATER_THAN, QueryValue.of(1)),
            item(popularity = null),
        ) shouldBe TruthValue.UNKNOWN
    }

    @Test
    fun `missing expanded metadata remains unknown instead of false`() {
        val target = item()

        evaluate(
            predicate(QueryField.START_DATE, QueryOperator.GREATER_OR_EQUAL, QueryValue.of("2020-01-01")),
            target,
        ) shouldBe TruthValue.UNKNOWN
        evaluate(
            predicate(QueryField.PUBLISHER, QueryOperator.EQUALS, QueryValue.of("Shueisha")),
            target,
        ) shouldBe TruthValue.UNKNOWN
        evaluate(
            predicate(QueryField.COUNTRY, QueryOperator.EQUALS, QueryValue.of("JP")),
            target,
        ) shouldBe TruthValue.UNKNOWN
        evaluate(
            predicate(QueryField.RANK, QueryOperator.LESS_THAN, QueryValue.of(100.0)),
            target,
        ) shouldBe TruthValue.UNKNOWN
    }

    @Test
    fun `no residual expression is pass through`() {
        ResidualEvaluator.support(null) shouldBe ResidualSupport.Supported
        evaluate(null, item()) shouldBe TruthValue.TRUE
    }

    @Test
    fun `unknown enum values remain unknown rather than matching string unknown`() {
        val statusExpression = predicate(
            QueryField.STATUS,
            QueryOperator.EQUALS,
            QueryValue.of("UNKNOWN"),
        )
        val formatExpression = predicate(
            QueryField.WORK_TYPE,
            QueryOperator.EQUALS,
            QueryValue.of("UNKNOWN"),
        )

        evaluate(statusExpression, item(status = CatalogItemStatus.UNKNOWN)) shouldBe TruthValue.UNKNOWN
        evaluate(formatExpression, item(format = CatalogItemFormat.UNKNOWN)) shouldBe TruthValue.UNKNOWN
    }

    private fun evaluate(expression: QueryExpression?, item: CatalogItem): TruthValue {
        return (ResidualEvaluator.evaluate(expression, item) as ResidualEvaluation.Evaluated).truth
    }

    private fun predicate(
        field: QueryField,
        operator: QueryOperator,
        value: QueryValue,
    ): QueryExpression.Predicate = QueryExpression.Predicate(field, operator, value)

    private fun item(
        providerId: String = "1",
        status: CatalogItemStatus = CatalogItemStatus.UNKNOWN,
        format: CatalogItemFormat = CatalogItemFormat.UNKNOWN,
        score: Double? = null,
        genres: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        authors: List<String> = emptyList(),
        artists: List<String> = emptyList(),
        publishers: List<String> = emptyList(),
        magazines: List<String> = emptyList(),
        categories: List<String> = emptyList(),
        demographics: List<String> = emptyList(),
        country: String? = null,
        popularity: Long? = null,
        favorites: Long? = null,
        rank: Double? = null,
        startDate: String? = null,
        endDate: String? = null,
        chapterCount: Int? = null,
        volumeCount: Int? = null,
    ): CatalogItem = CatalogItem(
        provider = "fake",
        providerId = providerId,
        title = "Title $providerId",
        status = status,
        format = format,
        score = score?.let { CatalogScore(provider = "fake", value = it) },
        authors = authors,
        artists = artists,
        genres = genres,
        tags = tags,
        publishers = publishers,
        magazines = magazines,
        categories = categories,
        demographics = demographics,
        country = country,
        popularity = popularity,
        favorites = favorites,
        rank = rank,
        startDate = startDate,
        endDate = endDate,
        chapterCount = chapterCount,
        volumeCount = volumeCount,
    )
}
