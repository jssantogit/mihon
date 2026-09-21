package tachiyomi.domain.tsuzuki.sync.adapter

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncRecordEnvelope
import tachiyomi.domain.tsuzuki.sync.service.CanonicalIdentitySyncRepository
import tachiyomi.domain.tsuzuki.sync.service.CanonicalTitleSyncSource
import tachiyomi.domain.tsuzuki.sync.service.SyncClock
import tachiyomi.domain.tsuzuki.sync.service.SyncDocumentAdapter
import tachiyomi.domain.tsuzuki.sync.service.SyncRevisionSource

class CanonicalTitlesSyncAdapter(
    private val titleSource: CanonicalTitleSyncSource,
    private val identitySource: CanonicalIdentitySyncRepository,
    private val titleRepository: CanonicalTitleRepository,
    private val revisionSource: SyncRevisionSource,
    private val clock: SyncClock,
) : SyncDocumentAdapter {

    override val documentKind = SyncDocumentKind.TITLES

    override suspend fun exportDocument(): SyncDocumentEnvelope {
        val identitiesByTitle = identitySource.getVerifiedIdentities()
            .groupBy { it.canonicalTitleId }
        val records = titleSource.getAllTitles()
            .sortedBy(CanonicalTitle::id)
            .associate { title ->
                title.id to SyncRecordEnvelope(
                    id = title.id,
                    revision = revisionSource.nextRevision(),
                    updatedAtEpochMillis = title.updatedAt,
                    fields = buildJsonObject {
                        put("displayTitle", title.displayTitle)
                        put("identityState", title.identityState.name)
                        put("createdAt", title.createdAt)
                        put(
                            "verifiedExternalIdentities",
                            buildJsonArray {
                                identitiesByTitle[title.id]
                                    .orEmpty()
                                    .sortedWith(
                                        compareBy(
                                            { it.provider },
                                            { it.externalId },
                                        ),
                                    )
                                    .forEach { identity ->
                                        add(
                                            buildJsonObject {
                                                put("provider", identity.provider)
                                                put("externalId", identity.externalId)
                                            },
                                        )
                                    }
                            },
                        )
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
            "Titles adapter cannot apply ${document.kind}"
        }

        document.records.values
            .sortedBy(SyncRecordEnvelope::id)
            .forEach { record ->
                require(!record.isTombstone) {
                    "Canonical title tombstones are not supported"
                }
                val title = record.toTitle()
                titleRepository.upsert(title)

                record.externalIdentities().forEach { (provider, externalId) ->
                    val existing = titleRepository.getByExternalIdentity(provider, externalId)
                    require(existing == null || existing.id == title.id) {
                        "Verified external identity is mapped to another canonical title"
                    }
                    if (existing == null) {
                        titleRepository.addExternalIdentity(
                            ExternalIdentity(
                                canonicalTitleId = title.id,
                                provider = provider,
                                externalId = externalId,
                                verified = true,
                                createdAt = title.createdAt,
                            ),
                        )
                    }
                }
            }
    }

    private fun SyncRecordEnvelope.toTitle(): CanonicalTitle {
        return CanonicalTitle(
            id = id,
            displayTitle = fields.requiredString("displayTitle"),
            identityState = CanonicalIdentityState.valueOf(
                fields.requiredString("identityState"),
            ),
            createdAt = fields.requiredLong("createdAt"),
            updatedAt = updatedAtEpochMillis,
        )
    }

    private fun SyncRecordEnvelope.externalIdentities(): List<Pair<String, String>> {
        val values = fields["verifiedExternalIdentities"] as? JsonArray ?: return emptyList()
        return values.map { value ->
            val objectValue = value.jsonObject
            objectValue.requiredString("provider") to
                objectValue.requiredString("externalId")
        }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}

private fun JsonObject.requiredString(name: String): String =
    getValue(name).jsonPrimitive.content

private fun JsonObject.requiredLong(name: String): Long =
    getValue(name).jsonPrimitive.long
