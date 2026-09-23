package eu.kanade.tachiyomi.data.tsuzuki.supabase

import android.content.Context
import tachiyomi.domain.tsuzuki.sync.service.SyncClientIdentityProvider
import java.io.File
import java.util.UUID

class SyncClientIdentityStore(
    private val directory: File,
    private val idSource: () -> String = { UUID.randomUUID().toString() },
) : SyncClientIdentityProvider {

    constructor(context: Context) : this(
        directory = context.noBackupFilesDir,
    )

    private val lock = Any()

    override fun getOrCreate(): String = synchronized(lock) {
        val file = File(directory, FILE_NAME)
        file.takeIf(File::isFile)
            ?.readText()
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { return@synchronized it }

        val generated = idSource().trim()
        require(generated.isNotBlank()) {
            "Generated Tsuzuki sync client identity must not be blank"
        }

        directory.mkdirs()
        val temp = File(directory, "$FILE_NAME.tmp")
        temp.writeText(generated)
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        generated
    }

    companion object {
        const val FILE_NAME = "tsuzuki_sync_client_id"
    }
}
