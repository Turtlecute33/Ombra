package app.ombra.data

import android.app.ActivityManager
import androidx.test.platform.app.InstrumentationRegistry
import app.ombra.core.model.VpnStatus
import app.ombra.ipc.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The spine on a real device or emulator: binding CoreService starts the :vpn process, which loads
 * the Rust core, initializes it and pushes a logged-out status over AIDL. A core without an account
 * makes no network call. Never log in or create an account here.
 */
class CoreSmokeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun vpnProcessRunsTheCoreAndPushesLoggedOutStatus() = runBlocking {
        val client = CoreClient(context)
        withContext(Dispatchers.Main) { client.bind() }
        try {
            withTimeout(TIMEOUT) {
                val status = client.status.filterNotNull().first()
                assertFalse(status.isLoggedIn)
                assertNull(status.accountId, "the status must be sanitized before it leaves :vpn")
                assertEquals(VpnStatus.Disconnected, status.vpnStatus)
                client.settings.filterNotNull().first()
                // A round trip through IPC into Rust and back; local only.
                client.trafficStats()
            }
        } finally {
            withContext(Dispatchers.Main) { client.unbind() }
        }

        val processes = context.getSystemService(ActivityManager::class.java).runningAppProcesses.map { it.processName }
        assertTrue("${context.packageName}:vpn" in processes, "no :vpn process in $processes")
        val maps = File("/proc/self/maps").readText()
        assertFalse("libobscuravpn_client.so" in maps, "the main process must never load the Rust core")
    }

    @Test
    fun requestWithoutAccountFailsCleanly() = runBlocking {
        val client = CoreClient(context)
        withContext(Dispatchers.Main) { client.bind() }
        try {
            // Rust refuses before any network call because no account is stored.
            val error = runCatching { withTimeout(TIMEOUT) { client.request(Request.RefreshAccount) } }.exceptionOrNull()
            assertEquals("notLoggedIn", (error as? CoreRequestException)?.code)
        } finally {
            withContext(Dispatchers.Main) { client.unbind() }
        }
    }

    private companion object {
        val TIMEOUT = 30.seconds
    }
}
