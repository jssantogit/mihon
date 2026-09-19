package tachiyomi.data.tsuzuki.collections

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

class CollectionQueryJsonCodecTest {

    @Test
    fun `query json v1 round trips all supported value shapes`() {
        val expression = QueryExpression.All(
            QueryExpression.Predicate(
                QueryField.STATUS,
                QueryOperator.EQUALS,
                QueryValue.of("ongoing"),
            ),
            QueryExpression.Predicate(
                QueryField.CHAPTER_COUNT,
                QueryOperator.BETWEEN,
                QueryValue.range(QueryValue.of(10), QueryValue.of(100)),
            ),
            QueryExpression.Predicate(
                QueryField.GENRE,
                QueryOperator.IN,
                QueryValue.of(listOf(QueryValue.of("Action"), QueryValue.of("Drama"))),
            ),
            QueryExpression.Predicate(
                QueryField.SCORE,
                QueryOperator.GREATER_OR_EQUAL,
                QueryValue.of(80.5),
            ),
            QueryExpression.Predicate(
                QueryField.IN_LIBRARY,
                QueryOperator.EQUALS,
                QueryValue.of(true),
            ),
            QueryExpression.Predicate(
                QueryField.START_DATE,
                QueryOperator.GREATER_OR_EQUAL,
                QueryValue.relative(
                    base = QueryValue.TemporalBase.TODAY,
                    offset = -30,
                    unit = QueryValue.TemporalUnit.DAYS,
                ),
            ),
        )

        val encoded = CollectionQueryJsonCodec.encode(expression)
        val decoded = CollectionQueryJsonCodec.decode(encoded)

        decoded shouldBe expression.normalize()
    }

    @Test
    fun `equivalent normalized expressions encode identically`() {
        val status = QueryExpression.Predicate(
            QueryField.STATUS,
            QueryOperator.EQUALS,
            QueryValue.of("ongoing"),
        )
        val genre = QueryExpression.Predicate(
            QueryField.GENRE,
            QueryOperator.EQUALS,
            QueryValue.of("Action"),
        )

        CollectionQueryJsonCodec.encode(QueryExpression.All(status, genre)) shouldBe
            CollectionQueryJsonCodec.encode(QueryExpression.All(genre, status))
    }

    @Test
    fun `unknown query json schema version fails closed`() {
        val expression = QueryExpression.Predicate(
            QueryField.STATUS,
            QueryOperator.EQUALS,
            QueryValue.of("ongoing"),
        )
        val encoded = CollectionQueryJsonCodec.encode(expression)
            .replace("\"schemaVersion\":1", "\"schemaVersion\":2")

        shouldThrow<IllegalArgumentException> {
            CollectionQueryJsonCodec.decode(encoded)
        }
    }
}
