package eu.kanade.tachiyomi.crash

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.content.FileProvider
import eu.kanade.presentation.crash.CrashScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import java.io.File

class CrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val exception = GlobalExceptionHandler.getThrowableFromIntent(intent)
        val persistentCrash = PersistentCrashLogStore(applicationContext).read()

        setContent {
            MaterialTheme {
                Surface {
                    CrashScreen(
                        exception = exception,
                        onShareLogsClick = {
                            shareCrashLog(
                                persistentCrash
                                    ?: exception?.stackTraceToString()
                                    ?: "No crash details were available.",
                            )
                        },
                        onRestartClick = {
                            finishAffinity()
                            startActivity(Intent(this@CrashActivity, MainActivity::class.java))
                        },
                    )
                }
            }
        }
    }

    private fun shareCrashLog(content: String) {
        runCatching {
            val report = File(cacheDir, CRASH_SHARE_FILE).apply {
                writeText(content)
            }
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.crash-provider",
                report,
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, null))
        }
    }

    private companion object {
        const val CRASH_SHARE_FILE = "tsuzuki_startup_crash.txt"
    }
}
