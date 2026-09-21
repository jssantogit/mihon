package tachiyomi.domain.tsuzuki.sync.service

import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind

interface SyncDocumentAdapter {
    val documentKind: SyncDocumentKind

    suspend fun exportDocument(): SyncDocumentEnvelope

    suspend fun applyDocument(document: SyncDocumentEnvelope)

    suspend fun hasUnportableLocalState(): Boolean = false
}
