package eu.kanade.tachiyomi.data.collections.query

/**
 * Strongly typed value wrappers supporting scalars, collections, ranges, and relative temporal expressions.
 */
sealed interface QueryValue : Comparable<QueryValue> {
    fun canonicalString(): String

    data class StringValue(val value: String) : QueryValue {
        override fun canonicalString(): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        override fun compareTo(other: QueryValue): Int = when (other) {
            is StringValue -> value.compareTo(other.value)
            else -> typePriority().compareTo(other.typePriority())
        }
    }

    data class IntegerValue(val value: Long) : QueryValue {
        constructor(intVal: Int) : this(intVal.toLong())
        override fun canonicalString(): String = value.toString()
        override fun compareTo(other: QueryValue): Int = when (other) {
            is IntegerValue -> value.compareTo(other.value)
            is DoubleValue -> (value.toDouble()).compareTo(other.value)
            else -> typePriority().compareTo(other.typePriority())
        }
    }

    data class DoubleValue(val value: Double) : QueryValue {
        override fun canonicalString(): String = value.toString()
        override fun compareTo(other: QueryValue): Int = when (other) {
            is DoubleValue -> value.compareTo(other.value)
            is IntegerValue -> value.compareTo(other.value.toDouble())
            else -> typePriority().compareTo(other.typePriority())
        }
    }

    data class BooleanValue(val value: Boolean) : QueryValue {
        override fun canonicalString(): String = value.toString()
        override fun compareTo(other: QueryValue): Int = when (other) {
            is BooleanValue -> value.compareTo(other.value)
            else -> typePriority().compareTo(other.typePriority())
        }
    }

    data class ListValue(val values: List<QueryValue>) : QueryValue {
        override fun canonicalString(): String = "[" + values.joinToString(", ") { it.canonicalString() } + "]"
        override fun compareTo(other: QueryValue): Int = when (other) {
            is ListValue -> {
                val sizeCmp = values.size.compareTo(other.values.size)
                if (sizeCmp != 0) {
                    sizeCmp
                } else {
                    var cmp = 0
                    for (i in values.indices) {
                        cmp = values[i].compareTo(other.values[i])
                        if (cmp != 0) break
                    }
                    cmp
                }
            }
            else -> typePriority().compareTo(other.typePriority())
        }
    }

    data class RangeValue(val lower: QueryValue, val upper: QueryValue) : QueryValue {
        override fun canonicalString(): String = "[${lower.canonicalString()}..${upper.canonicalString()}]"
        override fun compareTo(other: QueryValue): Int = when (other) {
            is RangeValue -> {
                val lowerCmp = lower.compareTo(other.lower)
                if (lowerCmp != 0) lowerCmp else upper.compareTo(other.upper)
            }
            else -> typePriority().compareTo(other.typePriority())
        }
    }

    enum class TemporalBase {
        TODAY,
        CURRENT_YEAR,
        NOW,
    }

    enum class TemporalUnit {
        DAYS,
        MONTHS,
        YEARS,
    }

    data class RelativeTemporal(
        val base: TemporalBase,
        val offset: Int = 0,
        val unit: TemporalUnit = TemporalUnit.DAYS,
    ) : QueryValue {
        override fun canonicalString(): String = "RELATIVE(${base.name},offset=$offset,unit=${unit.name})"
        override fun compareTo(other: QueryValue): Int = when (other) {
            is RelativeTemporal -> {
                val baseCmp = base.ordinal.compareTo(other.base.ordinal)
                if (baseCmp != 0) {
                    baseCmp
                } else {
                    val unitCmp = unit.ordinal.compareTo(other.unit.ordinal)
                    if (unitCmp != 0) unitCmp else offset.compareTo(other.offset)
                }
            }
            else -> typePriority().compareTo(other.typePriority())
        }
    }

    fun typePriority(): Int = when (this) {
        is BooleanValue -> 1
        is IntegerValue -> 2
        is DoubleValue -> 3
        is StringValue -> 4
        is RelativeTemporal -> 5
        is RangeValue -> 6
        is ListValue -> 7
    }

    companion object {
        fun of(value: String): QueryValue = StringValue(value)
        fun of(value: Long): QueryValue = IntegerValue(value)
        fun of(value: Int): QueryValue = IntegerValue(value.toLong())
        fun of(value: Double): QueryValue = DoubleValue(value)
        fun of(value: Boolean): QueryValue = BooleanValue(value)
        fun of(values: List<QueryValue>): QueryValue = ListValue(values)
        fun range(lower: QueryValue, upper: QueryValue): QueryValue = RangeValue(lower, upper)
        fun relative(base: TemporalBase, offset: Int = 0, unit: TemporalUnit = TemporalUnit.DAYS): QueryValue =
            RelativeTemporal(base, offset, unit)
    }
}
