package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultStructuredDiagnosticRecorder(
    private val history: LocalStructuredDiagnosticHistory,
) : StructuredDiagnosticRecorder {
    override val sessionId: String = UUID.randomUUID().toString()

    private val pseudonymSalt = ByteArray(PSEUDONYM_SALT_BYTES).also(SecureRandom()::nextBytes)
    private val pipeline by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        StructuredDiagnosticRecordingPipeline(
            persistenceEnabled = history::persistenceAllowed,
            logcatSink = { priority, encoded -> logcat(priority) { encoded } },
            historySink = history::submit,
        )
    }

    override fun canonicalTitleReference(canonicalTitleId: String): String? =
        pseudonymousReference("canonical", canonicalTitleId)

    override fun mihonMangaReference(mihonMangaId: Long): String? =
        mihonMangaId.takeIf { it > 0 }?.let { pseudonymousReference("mihon", it.toString()) }

    override fun record(event: StructuredDiagnosticEvent) {
        pipeline.record(event)
    }

    private fun pseudonymousReference(kind: String, identifier: String): String? {
        if (identifier.isBlank()) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(pseudonymSalt)
            digest.update(kind.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.digest(identifier.toByteArray(Charsets.UTF_8)).take(REFERENCE_BYTES).joinToString("") {
                "%02x".format(it)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val PSEUDONYM_SALT_BYTES = 32
        const val REFERENCE_BYTES = 16
    }
}
