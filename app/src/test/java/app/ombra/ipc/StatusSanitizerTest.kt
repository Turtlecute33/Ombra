package app.ombra.ipc

import app.ombra.core.model.CoreJson
import app.ombra.core.model.CoreModelTest
import app.ombra.core.model.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatusSanitizerTest {
    private val accountNumber = "55550123456780554337"
    private val raw = CoreJson.decodeFromString(Status.serializer(), CoreModelTest.statusJson("""{"disconnected":{}}"""))

    @Test
    fun removesTheAccountNumberEverywhere() {
        val clean = StatusSanitizer.sanitize(raw)
        assertNull(clean.accountId)
        assertNull(clean.account!!.accountInfo.id)
        assertTrue(clean.loggedIn)
        assertTrue(clean.isLoggedIn)
        assertFalse(accountNumber in StatusSanitizer.sanitizedJson(raw))
    }

    @Test
    fun keepsEverythingElse() {
        val clean = StatusSanitizer.sanitize(raw)
        assertEquals(raw.version, clean.version)
        assertEquals(raw.vpnStatus, clean.vpnStatus)
        assertEquals(raw.account!!.accountInfo.copy(id = null), clean.account!!.accountInfo)
        assertEquals(raw.pinnedLocations, clean.pinnedLocations)
        assertEquals(raw.dnsContentBlock, clean.dnsContentBlock)
    }

    @Test
    fun loggedOutStaysLoggedOut() {
        val clean = StatusSanitizer.sanitize(raw.copy(accountId = null, account = null))
        assertFalse(clean.loggedIn)
        assertFalse(clean.isLoggedIn)
    }

    @Test
    fun isIdempotentAndSurvivesIpc() {
        val once = StatusSanitizer.sanitize(raw)
        assertEquals(once, StatusSanitizer.sanitize(once))
        val received = IpcJson.decodeFromString(Status.serializer(), StatusSanitizer.sanitizedJson(raw))
        assertEquals(once, received)
        assertTrue(received.isLoggedIn)
    }
}
