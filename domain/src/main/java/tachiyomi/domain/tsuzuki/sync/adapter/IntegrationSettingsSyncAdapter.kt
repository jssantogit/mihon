package tachiyomi.domain.tsuzuki.sync.adapter

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.model.IntegrationSettings
import tachiyomi.domain.tsuzuki.integration.repository.IntegrationSettingsRepository
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncRecordEnvelope
import tachiyomi.domain.tsuzuki.sync.service.SyncClock
import tachiyomi.domain.tsuzuki.sync.service.SyncDocumentAdapter
import tachiyomi.domain.tsuzuki.sync.service.SyncRevisionSource

class IntegrationSettingsSyncAdapter(
    private val repository: IntegrationSettingsRepository,
    private val json: Json,
    private val revisionSource: SyncRevisionSource,
    private val clock: SyncClock,
) : SyncDocumentAdapter {

    override val documentKind = SyncDocumentKind.INTEGRATION_SETTINGS

    override suspend fun exportDocument(): SyncDocumentEnvelope {
        val records = repository.observeAll()
            .first()
            .sortedBy { it.integrationId.value }
            .associate { settings ->
                val id = settings.integrationId.value
                id to SyncRecordEnvelope(
                    id = id,
                    revision = revisionSource.nextRevision(),
                    updatedAtEpochMillis = settings.updatedAt,
                    fields = buildJsonObject {
                        put("enabled", settings.enabled)
                        put("config", sanitizeConfig(parseConfig(settings.configJson)))
                    },
                )
            }

        return SyncDocumentEnvelope(
            schemaVersion = SCHEMA_VERSION,
            kind = documentKind,
            revision = revisionSource.nextRevision(),
            generatedAtEpochMillis = clock.nowEpochMillis(),
            records = records,
        )
    }

    override suspend fun applyDocument(document: SyncDocumentEnvelope) {
        require(document.kind == documentKind) {
            "Integration settings adapter cannot apply ${document.kind}"
        }

        document.records.values
            .sortedBy(SyncRecordEnvelope::id)
            .forEach { record ->
                val id = IntegrationId(record.id)
                val existing = repository.get(id)

                if (record.isTombstone) {
                    if (existing != null) {
                        repository.upsert(
                            existing.copy(
                                enabled = false,
                                updatedAt = record.updatedAtEpochMillis,
                            ),
                        )
                    }
                    return@forEach
                }

                val remoteConfig = record.fields["config"]?.jsonObject
                    ?: JsonObject(emptyMap())
                val localConfig = existing
                    ?.configJson
                    ?.let(::parseConfig)
                    ?: JsonObject(emptyMap())
                val mergedConfig = mergeObjects(
                    remote = sanitizeConfig(remoteConfig).jsonObject,
                    localSecrets = extractSecrets(localConfig),
                )

                repository.upsert(
                    IntegrationSettings(
                        integrationId = id,
                        enabled = record.fields.getValue("enabled").jsonPrimitive.boolean,
                        configJson = json.encodeToString(
                            JsonObject.serializer(),
                            mergedConfig,
                        ),
                        updatedAt = record.updatedAtEpochMillis,
                    ),
                )
            }
    }

    private fun parseConfig(raw: String): JsonObject {
        return runCatching {
            json.parseToJsonElement(raw).jsonObject
        }.getOrElse {
            JsonObject(emptyMap())
        }
    }

    private fun sanitizeConfig(element: JsonElement): JsonElement {
        return when (element) {
            is JsonObject -> JsonObject(
                element.entries
                    .filterNot { (key, _) -> key.isSensitiveConfigKey() }
                    .associate { (key, value) ->
                        key to sanitizeConfig(value)
                    },
            )
            is JsonArray -> JsonArray(element.map(::sanitizeConfig))
            else -> element
        }
    }

    private fun extractSecrets(source: JsonObject): JsonObject {
        val secrets = linkedMapOf<String, JsonElement>()
        source.forEach { (key, value) ->
            when {
                key.isSensitiveConfigKey() -> secrets[key] = value
                value is JsonObject -> {
                    val nested = extractSecrets(value)
                    if (nested.isNotEmpty()) {
                        secrets[key] = nested
                    }
                }
            }
        }
        return JsonObject(secrets)
    }

    private fun mergeObjects(
        remote: JsonObject,
        localSecrets: JsonObject,
    ): JsonObject {
        val merged = remote.toMutableMap()
        localSecrets.forEach { (key, secretValue) ->
            val remoteValue = merged[key]
            merged[key] = if (remoteValue is JsonObject && secretValue is JsonObject) {
                mergeObjects(remoteValue, secretValue)
            } else {
                secretValue
            }
        }
        return JsonObject(merged)
    }

    private fun String.isSensitiveConfigKey(): Boolean {
        val normalized = lowercase()
            .replace("_", "")
            .replace("-", "")
        return SENSITIVE_KEY_MARKERS.any(normalized::contains)
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        val SENSITIVE_KEY_MARKERS = listOf(
            "token",
            "secret",
            "password",
            "credential",
            "apikey",
            "authorization",
            "cookie",
            "session",
        )
    }
}
