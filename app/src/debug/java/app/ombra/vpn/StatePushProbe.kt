package app.ombra.vpn

import android.app.Service
import android.content.Intent
import android.os.IBinder
import app.ombra.core.model.CachedValue
import app.ombra.core.model.ExitList
import app.ombra.core.model.OneExit
import app.ombra.core.model.OneRelay
import app.ombra.core.model.RelayPreferredExit
import app.ombra.core.model.Status
import app.ombra.ipc.IOmbraCore
import app.ombra.ipc.IResultCallback
import app.ombra.ipc.IStateListener
import app.ombra.ipc.Settings
import app.ombra.ipc.StatePush
import app.ombra.ipc.SystemState
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Base64
import kotlin.random.Random

/**
 * Debug builds only, for StatePushProbeTest: a stand-in for [CoreService] in a process of its own.
 * A new listener gets [EXITS] and [RELAYS] the way CoreService sends state ([StateFeed], then
 * [StatePush]), so large lists cross a real process boundary without an account. It loads no core
 * and makes no network call.
 */
class StatePushProbe : Service() {
    private val feed = StateFeed(
        MutableStateFlow<Status?>(null),
        MutableStateFlow(EXITS),
        MutableStateFlow(RELAYS),
        MutableStateFlow(Settings()),
        MutableStateFlow(SystemState()),
    )

    override fun onBind(intent: Intent): IBinder = object : IOmbraCore.Stub() {
        override fun call(requestJson: String?, cb: IResultCallback?) = Unit

        override fun addListener(l: IStateListener?) {
            if (l == null) return
            feed.snapshot().forEach { (kind, json) -> StatePush.of(json)?.sendTo(l, kind) }
        }

        override fun removeListener(l: IStateListener?) = Unit
    }

    companion object {
        private val CITIES = listOf(
            Triple("SE", "sto", "Stockholm"), Triple("DE", "dus", "Düsseldorf"), Triple("CH", "zrh", "Zürich"),
            Triple("US", "nyc", "New York, NY"), Triple("CA", "mtr", "Montréal"), Triple("BR", "sao", "São Paulo"),
            Triple("JP", "tyo", "Tokyo"), Triple("ZA", "jnb", "Johannesburg"),
        )
        private val random = Random(2000)

        /** The exit-list budget of docs/DESIGN.md section 6, with a provider URL per server. */
        val EXITS = CachedValue("probe", 1_700_000_000, ExitList(List(2000) { exit(it) }))
        val RELAYS = CachedValue("probe", 1_700_000_001, List(300) { relay(it) })

        private fun exit(i: Int): OneExit {
            val (country, city, name) = CITIES[i % CITIES.size]
            val providerId = "${country.lowercase()}-$city-wg-${i / CITIES.size + 1}"
            return OneExit(
                id = "${city.uppercase()}-$i",
                countryCode = country,
                cityCode = city,
                cityName = name,
                providerId = providerId,
                providerUrl = "https://mullvad.net/en/servers/$providerId",
                providerName = "Mullvad VPN",
                providerHomepageUrl = "https://mullvad.net",
                datacenterId = random.nextLong(1L shl 32),
                tier = random.nextInt(3),
            )
        }

        private fun relay(i: Int): OneRelay {
            val exit = EXITS.value.exits[i]
            return OneRelay(
                id = "relay-$i",
                countryCode = exit.countryCode,
                cityCode = exit.cityCode,
                cityName = exit.cityName,
                preferredExits = listOf(RelayPreferredExit(exit.id)),
                ipV4 = List(4) { random.nextInt(1, 255) }.joinToString("."),
                ports = listOf(443, 8443),
                tlsCert = Base64.getEncoder().encodeToString(random.nextBytes(440)),
            )
        }
    }
}
