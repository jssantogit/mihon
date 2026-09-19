package tachiyomi.domain.tsuzuki.collections.query

/**
 * Provider-neutral AST node representing a boolean or predicate expression.
 */
sealed interface QueryExpression : Comparable<QueryExpression> {

    fun normalize(): QueryExpression = QueryNormalizer.normalize(this)

    fun normalizedKey(): String = QueryNormalizer.normalizedKey(this)

    fun toCanonicalString(): String

    data class All(val expressions: List<QueryExpression>) : QueryExpression {
        init {
            if (expressions.isEmpty()) {
                throw QueryValidationException("ALL expression cannot be empty")
            }
        }

        constructor(vararg exprs: QueryExpression) : this(exprs.toList())

        override fun toCanonicalString(): String =
            "ALL(" + expressions.joinToString(", ") { it.toCanonicalString() } + ")"

        override fun compareTo(other: QueryExpression): Int = when (other) {
            is All -> {
                val sizeCmp = expressions.size.compareTo(other.expressions.size)
                if (sizeCmp != 0) {
                    sizeCmp
                } else {
                    var cmp = 0
                    for (i in expressions.indices) {
                        cmp = expressions[i].compareTo(other.expressions[i])
                        if (cmp != 0) break
                    }
                    cmp
                }
            }
            else -> typePriority().compareTo(other.typePriority())
        }
    }

    data class Any(val expressions: List<QueryExpression>) : QueryExpression {
        init {
            if (expressions.isEmpty()) {
                throw QueryValidationException("ANY expression cannot be empty")
            }
        }

        constructor(vararg exprs: QueryExpression) : this(exprs.toList())

        override fun toCanonicalString(): String =
            "ANY(" + expressions.joinToString(", ") { it.toCanonicalString() } + ")"

        override fun compareTo(other: QueryExpression): Int = when (other) {
            is Any -> {
                val sizeCmp = expressions.size.compareTo(other.expressions.size)
                if (sizeCmp != 0) {
                    sizeCmp
                } else {
                    var cmp = 0
                    for (i in expressions.indices) {
                        cmp = expressions[i].compareTo(other.expressions[i])
                        if (cmp != 0) break
                    }
                    cmp
                }
            }
            else -> typePriority().compareTo(other.typePriority())
        }
    }

    data class Not(val expression: QueryExpression) : QueryExpression {
        override fun toCanonicalString(): String = "NOT(${expression.toCanonicalString()})"

        override fun compareTo(other: QueryExpression): Int = when (other) {
            is Not -> expression.compareTo(other.expression)
            else -> typePriority().compareTo(other.typePriority())
        }
    }

    data class Predicate(
        val field: QueryField,
        val operator: QueryOperator,
        val value: QueryValue,
        val validate: Boolean = true,
    ) : QueryExpression {
        init {
            if (validate) {
                QueryValidator.validatePredicate(field, operator, value)
            }
        }

        override fun toCanonicalString(): String =
            "${field.identifier} ${operator.name} ${value.canonicalString()}"

        override fun compareTo(other: QueryExpression): Int = when (other) {
            is Predicate -> {
                val fieldCmp = field.compareTo(other.field)
                if (fieldCmp != 0) {
                    fieldCmp
                } else {
                    val opCmp = operator.ordinal.compareTo(other.operator.ordinal)
                    if (opCmp != 0) {
                        opCmp
                    } else {
                        value.compareTo(other.value)
                    }
                }
            }
            else -> typePriority().compareTo(other.typePriority())
        }
    }

    fun typePriority(): Int = when (this) {
        is Predicate -> 1
        is Not -> 2
        is All -> 3
        is Any -> 4
    }
}

fun QueryExpression.validate(): QueryValidationResult = QueryValidator.validate(this)
fun QueryExpression.validateOrThrow(): Unit = QueryValidator.validateOrThrow(this)
