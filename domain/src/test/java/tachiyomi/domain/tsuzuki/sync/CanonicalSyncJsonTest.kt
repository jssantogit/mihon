package tachiyomi.domain.tsuzuki.sync

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.sync.service.canonicalizeSyncJson

class CanonicalSyncJsonTest {

    @Test
    fun `case 1 - object keys canonicalize recursively`() {
        val input = buildJsonObject {
            put(
                "z",
                buildJsonObject {
                    put("b", 2)
                    put("a", 1)
                },
            )
            put("a", "first")
        }

        val canonical = canonicalizeSyncJson(input) as JsonObject

        canonical.keys.toList() shouldBe listOf("a", "z")
        (canonical.getValue("z") as JsonObject).keys.toList() shouldBe listOf("a", "b")
    }

    @Test
    fun `case 2 - equivalent objects with different insertion order encode identically`() {
        val left = JsonObject(
            linkedMapOf(
                "b" to JsonPrimitive(2),
                "a" to JsonPrimitive(1),
            ),
        )
        val right = JsonObject(
            linkedMapOf(
                "a" to JsonPrimitive(1),
                "b" to JsonPrimitive(2),
            ),
        )

        Json.encodeToString(
            JsonObject.serializer(),
            canonicalizeSyncJson(left) as JsonObject,
        ) shouldBe Json.encodeToString(
            JsonObject.serializer(),
            canonicalizeSyncJson(right) as JsonObject,
        )
    }

    @Test
    fun `case 3 - array order remains meaningful while nested objects canonicalize`() {
        val input = JsonArray(
            listOf(
                JsonObject(linkedMapOf("b" to JsonPrimitive(2), "a" to JsonPrimitive(1))),
                JsonPrimitive("second"),
            ),
        )

        val canonical = canonicalizeSyncJson(input) as JsonArray

        (canonical[0] as JsonObject).keys.toList() shouldBe listOf("a", "b")
        canonical[1] shouldBe JsonPrimitive("second")
    }
}
