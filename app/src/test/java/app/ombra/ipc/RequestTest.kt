package app.ombra.ipc

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RequestTest {
    private val all: List<Request> = listOf(
        Request.Login("55550123456780554337"),
        Request.CreateAccount,
        Request.EndNewAccountFlow,
        Request.Logout,
        Request.DeleteAccount,
        Request.RefreshAccount,
        Request.ServiceStatus,
        Request.RevealAccountNumber,
        Request.UpdateSettings(buildJsonObject { put("lanAccess", true) }),
        Request.TrafficStats,
        Request.RotateWgKey,
        Request.RefreshLists,
        Request.CreateDebugBundle("it is slow"),
        Request.CreateDebugBundle(),
        Request.RawCommand("""{"ping":{}}"""),
    )

    @Test
    fun everyRequestRoundTrips() {
        all.forEach { request ->
            val json = IpcJson.encodeToString(Request.serializer(), request)
            assertEquals(request, IpcJson.decodeFromString(Request.serializer(), json), json)
        }
    }

    @Test
    fun wireFormatUsesTypeDiscriminator() {
        assertEquals("""{"type":"logout"}""", IpcJson.encodeToString(Request.serializer(), Request.Logout))
        assertEquals(
            """{"type":"updateSettings","patch":{"lanAccess":true}}""",
            IpcJson.encodeToString(Request.serializer(), Request.UpdateSettings(buildJsonObject { put("lanAccess", true) })),
        )
    }

    @Test
    fun loginNeverPrintsTheNumber() {
        assertFalse("55550123456780554337" in Request.Login("55550123456780554337").toString())
    }
}
