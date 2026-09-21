package tachiyomi.domain.tsuzuki.sync.adapter

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tachiyomi.domain.tsuzuki.addon.model.AddonSyncIntent
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
import tachiyomi.domain.tsuzuki.addon.repository.AddonSyncIntentRepository
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncRecordEnvelope
import tachiyomi.domain.tsuzuki.sync.service.SyncClock
import tachiyomi.domain.tsuzuki.sync.service.SyncDocumentAdapter
import tachiyomi.domain.tsuzuki.sync.service.SyncRevisionSource

class AddonStateSyncAdapter(
    private val addonRepository: AddonRepository,
    private val intentRepository: AddonSyncIntentRepository,
    private val revisionSource: SyncRevisionSource,
    private val clock: SyncClock,
) : SyncDocumentAdapter {

    override val documentKind = SyncDocumentKind.ADDON_STATE

    override suspend fun exportDocument(): SyncDocumentEnvelope {
        val persisted = intentRepository.get()
        val installed = addonRepository.snapshot()
        val installedIds = installed.map { it.id.value }.toSet()
        val desired = persisted.desiredPackageIds + installedIds
        val enabled = (
            persisted.enabledPackageIds - installedIds
            ) + installed
            .filter { it.enabled }
            .map { it.id.value }

        val now = clock.nowEpochMillis()
        val record = SyncRecordEnvelope(
            id = GLOBAL_RECORD_ID,
            revision = revisionSource.nextRevision(),
            updatedAtEpochMillis = now,
            fields = buildJsonObject {
                put(
                    "desiredPackageIds",
                    JsonArray(desired.sorted().map(::JsonPrimitive)),
                )
                put(
                    "enabledPackageIds",
                    JsonArray(enabled.sorted().map(::JsonPrimitive)),
                )
            },
        )

        return SyncDocumentEnvelope(
            schemaVersion = SCHEMA_VERSION,
            kind = documentKind,
            revision = revisionSource.nextRevision(),
            generatedAtEpochMillis = now,
            records = mapOf(GLOBAL_RECORD_ID to record),
        )
    }

    override suspend fun applyDocument(document: SyncDocumentEnvelope) {
        require(document.kind == documentKind) {
            "Add-on state adapter cannot apply ${document.kind}"
        }

        val record = document.records[GLOBAL_RECORD_ID]
        if (record == null || record.isTombstone) {
            intentRepository.set(AddonSyncIntent())
            return
        }

        val desired = record.fields.getValue("desiredPackageIds")
            .jsonArray
            .map { it.jsonPrimitive.content }
            .toSet()
        val enabled = record.fields.getValue("enabledPackageIds")
            .jsonArray
            .map { it.jsonPrimitive.content }
            .toSet()

        intentRepository.set(
            AddonSyncIntent(
                desiredPackageIds = desired,
                enabledPackageIds = enabled,
            ),
        )
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val GLOBAL_RECORD_ID = "global"
    }
}
