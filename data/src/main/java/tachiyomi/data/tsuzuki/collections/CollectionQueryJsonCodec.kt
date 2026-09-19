package tachiyomi.data.tsuzuki.collections

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

object CollectionQueryJsonCodec {

    private const val SCHEMA_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    fun encode(expression: QueryExpression): String {
        val normalized = expression.normalize()
        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("schemaVersion", SCHEMA_VERSION)
                put("expression", encodeExpression(normalized))
            },
        )
    }

    fun decode(encoded: String): QueryExpression {
        val root = json.parseToJsonElement(encoded).jsonObject
        val schemaVersion = root.required("schemaVersion").jsonPrimitive.int
        require(schemaVersion == SCHEMA_VERSION) {
            "Unsupported Collections query JSON schemaVersion: $schemaVersion"
        }

        return decodeExpression(root.required("expression").jsonObject).normalize()
    }

    private fun encodeExpression(expression: QueryExpression): JsonObject = when (expression) {
        is QueryExpression.All -> buildJsonObject {
            put("type", "all")
            put(
                "children",
                buildJsonArray {
                    expression.expressions.forEach { add(encodeExpression(it)) }
                },
            )
        }
        is QueryExpression.Any -> buildJsonObject {
            put("type", "any")
            put(
                "children",
                buildJsonArray {
                    expression.expressions.forEach { add(encodeExpression(it)) }
                },
            )
        }
        is QueryExpression.Not -> buildJsonObject {
            put("type", "not")
            put("child", encodeExpression(expression.expression))
        }
        is QueryExpression.Predicate -> buildJsonObject {
            put("type", "predicate")
            put("field", expression.field.identifier)
            put("operator", expression.operator.name)
            put("value", encodeValue(expression.value))
        }
    }

    private fun decodeExpression(jsonObject: JsonObject): QueryExpression {
        return when (jsonObject.required("type").jsonPrimitive.content) {
            "all" -> QueryExpression.All(
                jsonObject.required("children").jsonArray.map { decodeExpression(it.jsonObject) },
            )
            "any" -> QueryExpression.Any(
                jsonObject.required("children").jsonArray.map { decodeExpression(it.jsonObject) },
            )
            "not" -> QueryExpression.Not(
                decodeExpression(jsonObject.required("child").jsonObject),
            )
            "predicate" -> QueryExpression.Predicate(
                field = QueryField.fromString(jsonObject.required("field").jsonPrimitive.content),
                operator = QueryOperator.valueOf(jsonObject.required("operator").jsonPrimitive.content),
                value = decodeValue(jsonObject.required("value").jsonObject),
            )
            else -> error("Unknown Collections query expression type")
        }
    }

    private fun encodeValue(value: QueryValue): JsonObject = when (value) {
        is QueryValue.StringValue -> typedValue("string", JsonPrimitive(value.value))
        is QueryValue.IntegerValue -> typedValue("integer", JsonPrimitive(value.value))
        is QueryValue.DoubleValue -> typedValue("double", JsonPrimitive(value.value))
        is QueryValue.BooleanValue -> typedValue("boolean", JsonPrimitive(value.value))
        is QueryValue.ListValue -> buildJsonObject {
            put("type", "list")
            put(
                "values",
                buildJsonArray {
                    value.values.forEach { add(encodeValue(it)) }
                },
            )
        }
        is QueryValue.RangeValue -> buildJsonObject {
            put("type", "range")
            put("lower", encodeValue(value.lower))
            put("upper", encodeValue(value.upper))
        }
        is QueryValue.RelativeTemporal -> buildJsonObject {
            put("type", "relative_temporal")
            put("base", value.base.name)
            put("offset", value.offset)
            put("unit", value.unit.name)
        }
    }

    private fun decodeValue(jsonObject: JsonObject): QueryValue {
        return when (jsonObject.required("type").jsonPrimitive.content) {
            "string" -> QueryValue.StringValue(jsonObject.required("value").jsonPrimitive.content)
            "integer" -> QueryValue.IntegerValue(jsonObject.required("value").jsonPrimitive.long)
            "double" -> QueryValue.DoubleValue(jsonObject.required("value").jsonPrimitive.double)
            "boolean" -> QueryValue.BooleanValue(jsonObject.required("value").jsonPrimitive.boolean)
            "list" -> QueryValue.ListValue(
                jsonObject.required("values").jsonArray.map { decodeValue(it.jsonObject) },
            )
            "range" -> QueryValue.RangeValue(
                lower = decodeValue(jsonObject.required("lower").jsonObject),
                upper = decodeValue(jsonObject.required("upper").jsonObject),
            )
            "relative_temporal" -> QueryValue.RelativeTemporal(
                base = QueryValue.TemporalBase.valueOf(jsonObject.required("base").jsonPrimitive.content),
                offset = jsonObject.required("offset").jsonPrimitive.int,
                unit = QueryValue.TemporalUnit.valueOf(jsonObject.required("unit").jsonPrimitive.content),
            )
            else -> error("Unknown Collections query value type")
        }
    }

    private fun typedValue(type: String, value: JsonPrimitive): JsonObject = buildJsonObject {
        put("type", type)
        put("value", value)
    }

    private fun JsonObject.required(name: String) = requireNotNull(this[name]) {
        "Missing required Collections query JSON field: $name"
    }
}
