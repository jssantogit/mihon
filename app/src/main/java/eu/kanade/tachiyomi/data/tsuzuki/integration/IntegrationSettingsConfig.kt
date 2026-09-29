package eu.kanade.tachiyomi.data.tsuzuki.integration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability

internal data class IntegrationSettingsConfig(
    private val values: JsonObject,
) {
    fun capabilityEnabled(capability: IntegrationCapability): Boolean =
        values[capability.configKey]
            ?.jsonPrimitive
            ?.booleanOrNull
            ?: true

    fun withCapability(
        capability: IntegrationCapability,
        enabled: Boolean,
    ): IntegrationSettingsConfig = IntegrationSettingsConfig(
        buildJsonObject {
            values.forEach { (key, value) -> put(key, value) }
            put(capability.configKey, enabled)
        },
    )

    fun isDefault(): Boolean = values.isEmpty()

    fun encode(): String = values.toString()

    companion object {
        fun decode(configJson: String?): IntegrationSettingsConfig {
            val parsed = runCatching {
                Json.parseToJsonElement(configJson.orEmpty().ifBlank { "{}" }) as? JsonObject
            }.getOrNull()
            return IntegrationSettingsConfig(parsed ?: JsonObject(emptyMap()))
        }
    }
}
