package tachiyomi.domain.tsuzuki.collections.query

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class QueryNormalizerTest {

    private val pAuthorA = QueryExpression.Predicate(
        field = QueryField.AUTHOR,
        operator = QueryOperator.EQUALS,
        value = QueryValue.of("Author A"),
    )

    private val pAuthorB = QueryExpression.Predicate(
        field = QueryField.AUTHOR,
        operator = QueryOperator.EQUALS,
        value = QueryValue.of("Author B"),
    )

    private val pStatusOngoing = QueryExpression.Predicate(
        field = QueryField.STATUS,
        operator = QueryOperator.EQUALS,
        value = QueryValue.of("ongoing"),
    )

    private val pScoreGte80 = QueryExpression.Predicate(
        field = QueryField.SCORE,
        operator = QueryOperator.GREATER_OR_EQUAL,
        value = QueryValue.of(80L),
    )

    // 1. Provider-neutral AST construction
    @Test
    fun `test 1 provider-neutral AST construction`() {
        val expr = QueryExpression.All(
            pAuthorA,
            QueryExpression.Any(
                pStatusOngoing,
                QueryExpression.Predicate(
                    field = QueryField.CHAPTER_COUNT,
                    operator = QueryOperator.GREATER_THAN,
                    value = QueryValue.of(50L),
                ),
            ),
            QueryExpression.Not(
                QueryExpression.Predicate(
                    field = QueryField.IN_LIBRARY,
                    operator = QueryOperator.EQUALS,
                    value = QueryValue.of(true),
                ),
            ),
            QueryExpression.Predicate(
                field = QueryField.Custom("user_tag"),
                operator = QueryOperator.CONTAINS,
                value = QueryValue.of("fantasy"),
            ),
        )

        expr.shouldBeInstanceOf<QueryExpression.All>()
        expr.expressions.size shouldBe 4
        QueryValidator.validate(expr) shouldBe QueryValidationResult.Valid
    }

    // 2. Nested ALL normalization
    @Test
    fun `test 2 nested ALL normalization`() {
        val nestedAll = QueryExpression.All(
            pAuthorA,
            QueryExpression.All(
                pAuthorB,
                pStatusOngoing,
            ),
        )

        val normalized = nestedAll.normalize()
        normalized.shouldBeInstanceOf<QueryExpression.All>()
        (normalized as QueryExpression.All).expressions shouldBe listOf(
            pAuthorA,
            pAuthorB,
            pStatusOngoing,
        )

        // Deeply nested
        val deepAll = QueryExpression.All(
            QueryExpression.All(pAuthorA, pAuthorB),
            QueryExpression.All(pStatusOngoing, QueryExpression.All(pScoreGte80)),
        )
        val deepNormalized = deepAll.normalize() as QueryExpression.All
        deepNormalized.expressions shouldBe listOf(
            pAuthorA,
            pAuthorB,
            pScoreGte80,
            pStatusOngoing,
        )
    }

    // 3. Nested ANY normalization
    @Test
    fun `test 3 nested ANY normalization`() {
        val nestedAny = QueryExpression.Any(
            pAuthorA,
            QueryExpression.Any(
                pAuthorB,
                pStatusOngoing,
            ),
        )

        val normalized = nestedAny.normalize()
        normalized.shouldBeInstanceOf<QueryExpression.Any>()
        (normalized as QueryExpression.Any).expressions shouldBe listOf(
            pAuthorA,
            pAuthorB,
            pStatusOngoing,
        )

        // Deeply nested
        val deepAny = QueryExpression.Any(
            QueryExpression.Any(pAuthorA, pAuthorB),
            QueryExpression.Any(pStatusOngoing, QueryExpression.Any(pScoreGte80)),
        )
        val deepNormalized = deepAny.normalize() as QueryExpression.Any
        deepNormalized.expressions shouldBe listOf(
            pAuthorA,
            pAuthorB,
            pScoreGte80,
            pStatusOngoing,
        )
    }

    // 4. Commutative ordering produces identical normalized identity
    @Test
    fun `test 4 commutative ordering produces identical normalized identity`() {
        val all1 = QueryExpression.All(pStatusOngoing, pAuthorA, pScoreGte80)
        val all2 = QueryExpression.All(pScoreGte80, pAuthorA, pStatusOngoing)
        val all3 = QueryExpression.All(pAuthorA, pScoreGte80, pStatusOngoing)

        val norm1 = all1.normalize()
        val norm2 = all2.normalize()
        val norm3 = all3.normalize()

        norm1 shouldBe norm2
        norm2 shouldBe norm3
        all1.normalizedKey() shouldBe all2.normalizedKey()
        all2.normalizedKey() shouldBe all3.normalizedKey()

        val any1 = QueryExpression.Any(pStatusOngoing, pAuthorA)
        val any2 = QueryExpression.Any(pAuthorA, pStatusOngoing)
        any1.normalize() shouldBe any2.normalize()
        any1.normalizedKey() shouldBe any2.normalizedKey()
    }

    // 5. Duplicate predicates normalize deterministically
    @Test
    fun `test 5 duplicate predicates normalize deterministically`() {
        val allWithDuplicates = QueryExpression.All(
            pAuthorA,
            pStatusOngoing,
            pAuthorA,
            pStatusOngoing,
            pAuthorB,
        )

        val normalized = allWithDuplicates.normalize() as QueryExpression.All
        normalized.expressions shouldBe listOf(pAuthorA, pAuthorB, pStatusOngoing)
        allWithDuplicates.normalizedKey() shouldBe
            QueryExpression.All(pAuthorA, pAuthorB, pStatusOngoing).normalizedKey()
    }

    // 6. IN values normalize deterministically
    @Test
    fun `test 6 IN values normalize deterministically`() {
        val inWithDuplicates = QueryExpression.Predicate(
            field = QueryField.GENRE,
            operator = QueryOperator.IN,
            value = QueryValue.ListValue(
                listOf(
                    QueryValue.of("Fantasy"),
                    QueryValue.of("Action"),
                    QueryValue.of("Fantasy"),
                    QueryValue.of("Adventure"),
                ),
            ),
        )

        val inSorted = QueryExpression.Predicate(
            field = QueryField.GENRE,
            operator = QueryOperator.IN,
            value = QueryValue.ListValue(
                listOf(
                    QueryValue.of("Action"),
                    QueryValue.of("Adventure"),
                    QueryValue.of("Fantasy"),
                ),
            ),
        )

        val normalized = inWithDuplicates.normalize()
        normalized shouldBe inSorted
        inWithDuplicates.normalizedKey() shouldBe inSorted.normalizedKey()
    }

    // 7. NOT recursively normalizes its child
    @Test
    fun `test 7 NOT recursively normalizes its child`() {
        val notExpr = QueryExpression.Not(
            QueryExpression.All(
                pStatusOngoing,
                QueryExpression.All(pAuthorA, pAuthorA),
            ),
        )

        val normalized = notExpr.normalize()
        val expected = QueryExpression.Not(
            QueryExpression.All(pAuthorA, pStatusOngoing),
        )
        normalized shouldBe expected
        notExpr.normalizedKey() shouldBe expected.normalizedKey()
    }

    // 8. Incompatible operator-value pairs are rejected
    @Test
    fun `test 8 incompatible operator-value pairs are rejected`() {
        // BETWEEN requires RangeValue
        shouldThrow<QueryValidationException> {
            QueryExpression.Predicate(
                field = QueryField.SCORE,
                operator = QueryOperator.BETWEEN,
                value = QueryValue.of("invalid"),
            )
        }

        // BETWEEN lower bound > upper bound
        shouldThrow<QueryValidationException> {
            QueryExpression.Predicate(
                field = QueryField.SCORE,
                operator = QueryOperator.BETWEEN,
                value = QueryValue.RangeValue(QueryValue.of(90L), QueryValue.of(50L)),
            )
        }

        // IN requires ListValue
        shouldThrow<QueryValidationException> {
            QueryExpression.Predicate(
                field = QueryField.GENRE,
                operator = QueryOperator.IN,
                value = QueryValue.of("scalar"),
            )
        }

        // IN requires non-empty list
        shouldThrow<QueryValidationException> {
            QueryExpression.Predicate(
                field = QueryField.GENRE,
                operator = QueryOperator.IN,
                value = QueryValue.ListValue(emptyList()),
            )
        }

        // Numeric operator requires numeric/temporal
        shouldThrow<QueryValidationException> {
            QueryExpression.Predicate(
                field = QueryField.SCORE,
                operator = QueryOperator.GREATER_THAN,
                value = QueryValue.of("high"),
            )
        }

        shouldThrow<QueryValidationException> {
            QueryExpression.Predicate(
                field = QueryField.SCORE,
                operator = QueryOperator.LESS_THAN,
                value = QueryValue.of(true),
            )
        }

        // CONTAINS rejects boolean or range
        shouldThrow<QueryValidationException> {
            QueryExpression.Predicate(
                field = QueryField.AUTHOR,
                operator = QueryOperator.CONTAINS,
                value = QueryValue.of(true),
            )
        }

        // Empty All and Any are rejected
        shouldThrow<QueryValidationException> {
            QueryExpression.All(emptyList())
        }

        shouldThrow<QueryValidationException> {
            QueryExpression.Any(emptyList())
        }

        // Non-throwing validation check returns Invalid
        val unvalidatedPredicate = QueryExpression.Predicate(
            field = QueryField.SCORE,
            operator = QueryOperator.BETWEEN,
            value = QueryValue.of(10L),
            validate = false,
        )
        val validationResult = QueryValidator.validate(unvalidatedPredicate)
        validationResult.shouldBeInstanceOf<QueryValidationResult.Invalid>()
    }

    // 9. No provider-specific type is required by the query domain
    @Test
    fun `test 9 no provider-specific type is required by the query domain`() {
        val queryPackage = QueryExpression::class.java.packageName
        val classes = listOf(
            QueryExpression::class.java,
            QueryField::class.java,
            QueryOperator::class.java,
            QueryValue::class.java,
            QueryNormalizer::class.java,
            QueryValidator::class.java,
            QueryValidationException::class.java,
            QueryValidationResult::class.java,
        )

        for (clazz in classes) {
            clazz.packageName shouldBe queryPackage
            for (declaredField in clazz.declaredFields) {
                val fieldType = declaredField.type.name
                fieldType.contains("kitsu", ignoreCase = true) shouldBe false
                fieldType.contains("mihon", ignoreCase = true) shouldBe false
                fieldType.contains("source", ignoreCase = true) shouldBe false
            }
            for (method in clazz.declaredMethods) {
                val returnType = method.returnType.name
                returnType.contains("kitsu", ignoreCase = true) shouldBe false
                returnType.contains("mihon", ignoreCase = true) shouldBe false
                returnType.contains("source", ignoreCase = true) shouldBe false
            }
        }
    }

    // 10. Normalized identity is stable across repeated normalization (idempotent)
    @Test
    fun `test 10 normalized identity is stable across repeated normalization`() {
        val expr = QueryExpression.All(
            QueryExpression.All(pStatusOngoing, pAuthorA),
            QueryExpression.Any(pScoreGte80, pAuthorB),
            QueryExpression.Not(pAuthorA),
        )

        val normOnce = expr.normalize()
        val normTwice = normOnce.normalize()
        val normThrice = normTwice.normalize()

        normTwice shouldBe normOnce
        normThrice shouldBe normOnce
        expr.normalizedKey() shouldBe normOnce.normalizedKey()
        normOnce.normalizedKey() shouldBe normTwice.normalizedKey()
    }

    // 11. Normalization does not mutate the original semantic expression
    @Test
    fun `test 11 normalization does not mutate the original semantic expression`() {
        val inner = QueryExpression.All(pStatusOngoing, pAuthorA)
        val outer = QueryExpression.All(inner, pScoreGte80)

        // Before normalization
        inner.expressions shouldBe listOf(pStatusOngoing, pAuthorA)
        outer.expressions shouldBe listOf(inner, pScoreGte80)

        val normalized = outer.normalize()

        // After normalization: original objects remain completely unchanged
        inner.expressions shouldBe listOf(pStatusOngoing, pAuthorA)
        outer.expressions shouldBe listOf(inner, pScoreGte80)

        normalized.shouldBeInstanceOf<QueryExpression.All>()
        (normalized as QueryExpression.All).expressions shouldBe listOf(
            pAuthorA,
            pScoreGte80,
            pStatusOngoing,
        )
    }

    // 12. Structurally different non-equivalent queries do not accidentally share the same normalized identity
    @Test
    fun `test 12 structurally different non-equivalent queries do not share normalized identity`() {
        val allExpr = QueryExpression.All(pAuthorA, pStatusOngoing)
        val anyExpr = QueryExpression.Any(pAuthorA, pStatusOngoing)
        val notExpr = QueryExpression.Not(QueryExpression.All(pAuthorA, pStatusOngoing))

        allExpr.normalizedKey() shouldNotBe anyExpr.normalizedKey()
        allExpr.normalizedKey() shouldNotBe notExpr.normalizedKey()
        anyExpr.normalizedKey() shouldNotBe notExpr.normalizedKey()

        val eqPred = QueryExpression.Predicate(
            field = QueryField.SCORE,
            operator = QueryOperator.EQUALS,
            value = QueryValue.of(10L),
        )
        val neqPred = QueryExpression.Predicate(
            field = QueryField.SCORE,
            operator = QueryOperator.NOT_EQUALS,
            value = QueryValue.of(10L),
        )
        val differentValuePred = QueryExpression.Predicate(
            field = QueryField.SCORE,
            operator = QueryOperator.EQUALS,
            value = QueryValue.of(20L),
        )
        val differentFieldPred = QueryExpression.Predicate(
            field = QueryField.RANK,
            operator = QueryOperator.EQUALS,
            value = QueryValue.of(10L),
        )

        eqPred.normalizedKey() shouldNotBe neqPred.normalizedKey()
        eqPred.normalizedKey() shouldNotBe differentValuePred.normalizedKey()
        eqPred.normalizedKey() shouldNotBe differentFieldPred.normalizedKey()
    }
}
