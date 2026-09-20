package tachiyomi.domain.tsuzuki.sync.service

import tachiyomi.domain.tsuzuki.sync.model.SyncCodecResult
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncFailure
import tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason
import tachiyomi.domain.tsuzuki.sync.model.SyncRemoteContent
import tachiyomi.domain.tsuzuki.sync.model.SyncRemoteFile
import tachiyomi.domain.tsuzuki.sync.model.SyncReplicaGenesis
import tachiyomi.domain.tsuzuki.sync.model.SyncReplicaJournal

class SyncReplicaBootstrap(
    private val documentCodec: SyncDocumentCodec,
) {

    fun resolveGenesis(
        documentKind: SyncDocumentKind,
        legacyFiles: List<SyncRemoteContent>,
        journals: List<SyncReplicaJournal>,
    ): SyncReplicaBootstrapResult {
        if (legacyFiles.size > 1) return malformed()

        val journalGenesis = journals.map { it.genesis }
        if (journalGenesis.any { it != null } && journalGenesis.any { it == null }) {
            return malformed()
        }
        val copiedGenesis = journalGenesis.filterNotNull().firstOrNull()
        if (copiedGenesis != null && journalGenesis.filterNotNull().any { it != copiedGenesis }) {
            return malformed()
        }

        val legacy = legacyFiles.singleOrNull()
        if (copiedGenesis != null) {
            if (legacy != null) {
                val decoded = decodeLegacy(documentKind, legacy)
                    ?: return malformed()
                if (
                    copiedGenesis.sourceRemoteId != legacy.file.remoteId ||
                    copiedGenesis.sourceRevisionToken != legacy.file.revision.revisionToken ||
                    copiedGenesis.document != decoded
                ) {
                    return malformed()
                }
            }
            return SyncReplicaBootstrapResult.Success(
                genesis = copiedGenesis,
                legacyFile = legacy?.file,
            )
        }

        if (journals.isNotEmpty()) {
            return if (legacy == null) {
                SyncReplicaBootstrapResult.Success(
                    genesis = null,
                    legacyFile = null,
                )
            } else {
                malformed()
            }
        }

        if (legacy == null) {
            return SyncReplicaBootstrapResult.Success(
                genesis = null,
                legacyFile = null,
            )
        }

        val document = decodeLegacy(documentKind, legacy)
            ?: return malformed()
        return SyncReplicaBootstrapResult.Success(
            genesis = SyncReplicaGenesis(
                sourceRemoteId = legacy.file.remoteId,
                sourceRevisionToken = legacy.file.revision.revisionToken,
                document = document,
            ),
            legacyFile = legacy.file,
        )
    }

    private fun decodeLegacy(
        documentKind: SyncDocumentKind,
        legacy: SyncRemoteContent,
    ) = when (val decoded = documentCodec.decode(legacy.content)) {
        is SyncCodecResult.Success -> decoded.value.takeIf {
            it.kind == documentKind
        }
        is SyncCodecResult.Failure -> null
    }

    private fun malformed() = SyncReplicaBootstrapResult.Failure(
        SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT),
    )
}

sealed interface SyncReplicaBootstrapResult {

    data class Success(
        val genesis: SyncReplicaGenesis?,
        val legacyFile: SyncRemoteFile?,
    ) : SyncReplicaBootstrapResult

    data class Failure(
        val failure: SyncFailure,
    ) : SyncReplicaBootstrapResult
}
