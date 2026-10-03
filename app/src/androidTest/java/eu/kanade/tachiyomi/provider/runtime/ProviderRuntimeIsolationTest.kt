package eu.kanade.tachiyomi.provider.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ProviderRuntimeIsolationTest {

    @Test
    fun providerRuntime_isIsolatedAndInterruptible() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connected = CountDownLatch(1)
        var remote: IProviderRuntimeService? = null

        val connection = object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?,
            ) {
                remote = IProviderRuntimeService.Stub.asInterface(service)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                remote = null
            }
        }

        val bound = context.bindService(
            Intent(context, ProviderRuntimeService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )

        try {
            assertTrue("isolated service should bind", bound)
            assertTrue("isolated service should connect", connected.await(5, TimeUnit.SECONDS))

            val runtime = requireNotNull(remote)
            assertNotEquals("provider runtime must use a distinct isolated UID", Process.myUid(), runtime.processUid())
            assertNotEquals("provider runtime must run in a distinct process", Process.myPid(), runtime.processPid())
            assertEquals("ok:3", runtime.evaluate("1 + 2", 1_000L, 500L))
            assertEquals(
                "error:TIMEOUT",
                runtime.evaluate("while (true) {}", 500L, 100L),
            )
        } finally {
            if (bound) {
                context.unbindService(connection)
            }
        }
    }
}
