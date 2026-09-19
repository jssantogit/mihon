package eu.kanade.tachiyomi.data.tsuzuki.googleauth

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAccountIdentity
import java.io.File

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class NoBackupGoogleAccountHintStore internal constructor(
    private val file: File,
) : GoogleAccountHintStore {

    @Inject
    constructor(context: Context) : this(
        File(context.noBackupFilesDir, FILE_NAME),
    )

    override fun read(): GoogleAccountIdentity? {
        val accountName = runCatching {
            file.takeIf(File::isFile)
                ?.readText()
                ?.trim()
                ?.takeIf(String::isNotBlank)
        }.getOrNull() ?: return null

        return runCatching {
            GoogleAccountIdentity(accountName)
        }.getOrNull()
    }

    override fun write(account: GoogleAccountIdentity) {
        file.parentFile?.mkdirs()
        file.writeText(account.accountName)
    }

    override fun clear() {
        runCatching {
            file.delete()
        }
    }

    private companion object {
        const val FILE_NAME = "tsuzuki-google-account-hint"
    }
}
