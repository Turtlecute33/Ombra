package app.ombra.vpn

import app.ombra.ipc.SystemState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NetworkFactsTest {
    private val wlan = LinkFacts(
        interfaceName = "wlan0",
        interfaceIndex = 30,
        mtu = 1500,
        privateDnsServer = "dns.example",
    )

    @Test
    fun clatInterfaceLowersTheMtu() {
        // 464XLAT on an IPv6-only network: IPv4 packets go through v4-<iface>.
        assertEquals(1472, clatAwareMtu(interfaceMtu = 1500, clatMtu = 1472))
    }

    @Test
    fun baseMtuWithoutClatInterface() {
        assertEquals(1500, clatAwareMtu(interfaceMtu = 1500, clatMtu = 0))
    }

    @Test
    fun clatMtuWhenTheBaseMtuIsUnknown() {
        assertEquals(1280, clatAwareMtu(interfaceMtu = 0, clatMtu = 1280))
    }

    @Test
    fun unknownMtuIsZero() {
        assertEquals(0, clatAwareMtu(interfaceMtu = 0, clatMtu = -1))
    }

    @Test
    fun rustGetsNameIndexHandleAndMtuHint() {
        assertEquals(RustNetwork("wlan0", 30, 0x1234L, 1500), rustNetwork(0x1234L, wlan))
    }

    @Test
    fun rustGetsNoNetworkWithoutUsableInterface() {
        assertNull(rustNetwork(1L, null))
        assertNull(rustNetwork(1L, wlan.copy(interfaceName = null)))
        assertNull(rustNetwork(1L, wlan.copy(interfaceIndex = -1)))
        assertNull(rustNetwork(1L, wlan.copy(interfaceIndex = 0)))
    }

    @Test
    fun networkFactsFillTheSystemState() {
        val before = SystemState(alwaysOn = true, lockdown = false, backgroundRestricted = true)
        val after = before.withNetwork(wlan)
        assertEquals(
            SystemState(alwaysOn = true, lockdown = false, privateDnsServer = "dns.example", backgroundRestricted = true),
            after,
        )
    }

    @Test
    fun noNetworkClearsTheNetworkFactsOnly() {
        val before = SystemState(alwaysOn = false).withNetwork(wlan)
        assertEquals(SystemState(alwaysOn = false), before.withNetwork(null))
    }
}
