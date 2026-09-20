package tachiyomi.domain.tsuzuki.sync.service

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

fun canonicalizeSyncJson(element: JsonElement): JsonElement {
    return when (element) {
        is JsonObject -> {
            val canonicalEntries = element.entries
                .sortedBy { it.key }
                .associateTo(linkedMapOf()) { (key, value) ->
                    key to canonicalizeSyncJson(value)
                }
            JsonObject(canonicalEntries)
        }

        is JsonArray -> JsonArray(element.map(::canonicalizeSyncJson))
        else -> element
    }
}
