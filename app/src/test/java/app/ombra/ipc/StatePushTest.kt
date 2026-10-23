package app.ombra.ipc

import app.ombra.core.model.CachedValue
import app.ombra.core.model.ExitList
import app.ombra.core.model.OneExit
import app.ombra.core.model.OneRelay
import app.ombra.core.model.RelayPreferredExit
import app.ombra.core.model.Status
import app.ombra.vpn.StateFeed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.builtins.ListSerializer
import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatePushTest {
    private val received = mutableListOf<Pair<Int, String>>()
    private val listener = object : StateListener() {
        override fun onStateJson(kind: Int, json: String) {
            received += kind to json
        }
    }

    @Test
    fun jsonUpToTheLimitGoesAsString() {
        val json = jsonOfLength(StatePush.MAX_PLAIN_CHARS)
        val push = assertIs<StatePush.Plain>(StatePush.of(json))
        assertEquals(json, push.json)

        push.sendTo(listener, StateKind.STATUS)
        assertEquals(listOf(StateKind.STATUS to json), received)
    }

    @Test
    fun longerJsonGoesCompressedAndArrivesUnchanged() {
        val json = jsonOfLength(StatePush.MAX_PLAIN_CHARS + 1)
        val push = assertIs<StatePush.Gzip>(StatePush.of(json))
        assertEquals(json, StatePush.gunzip(push.gzJson))

        push.sendTo(listener, StateKind.SETTINGS)
        assertEquals(listOf(StateKind.SETTINGS to json), received)
    }

    @Test
    fun twoThousandExitsAndThreeHundredRelaysFitWithRoomToSpare() {
        val exits = CachedValue("AAECAw==", 1_700_000_000, ExitList(exits(2000)))
        val relays = CachedValue("AAEC", 1_700_000_001, relays(300, exits.value.exits))
        val feed = StateFeed(
            MutableStateFlow<Status?>(null),
            MutableStateFlow(exits),
            MutableStateFlow(relays),
            MutableStateFlow(Settings()),
            MutableStateFlow(SystemState()),
        )
        val sent = feed.snapshot().toMap()

        val exitsPush = assertIs<StatePush.Gzip>(StatePush.of(sent.getValue(StateKind.EXITS)))
        assertTrue(exitsPush.gzJson.size < StatePush.MAX_GZIP_BYTES / 2, "2000 exits: ${exitsPush.gzJson.size} bytes compressed")
        val relaysPush = assertIs<StatePush.Gzip>(StatePush.of(sent.getValue(StateKind.RELAYS)))
        assertTrue(relaysPush.gzJson.size < StatePush.MAX_GZIP_BYTES / 8, "300 relays: ${relaysPush.gzJson.size} bytes compressed")

        exitsPush.sendTo(listener, StateKind.EXITS)
        relaysPush.sendTo(listener, StateKind.RELAYS)
        val (exitsJson, relaysJson) = received.map { it.second }
        assertEquals(exits, IpcJson.decodeFromString(CachedValue.serializer(ExitList.serializer()), exitsJson))
        assertEquals(
            relays.value.map { it.copy(tlsCert = "") },
            IpcJson.decodeFromString(CachedValue.serializer(ListSerializer(OneRelay.serializer())), relaysJson).value,
        )
    }

    @Test
    fun jsonTooLargeEvenCompressedIsNotSent() {
        // Random base64 hardly compresses: 6 bits of information per char.
        val bytes = Random(1).nextBytes(StatePush.MAX_GZIP_BYTES * 2)
        assertNull(StatePush.of(jsonString(Base64.getEncoder().encodeToString(bytes))))
    }

    @Test
    fun badCompressedPushIsDropped() {
        val gzJson = StatePush.gzip(jsonOfLength(StatePush.MAX_PLAIN_CHARS * 2))
        listener.onStateGz(StateKind.EXITS, gzJson.copyOf(gzJson.size / 2))
        listener.onStateGz(StateKind.EXITS, "{}".encodeToByteArray())
        listener.onStateGz(StateKind.EXITS, null)
        listener.onState(StateKind.EXITS, null)
        assertEquals(emptyList(), received)
    }

    private fun jsonString(text: String): String = "\"$text\""

    private fun jsonOfLength(length: Int): String = jsonString("x".repeat(length - 2))

    private data class City(val country: String, val code: String, val name: String)

    private companion object {
        /** Mullvad locations, spelled like the API spells them. */
        val CITIES = listOf(
            City("SE", "sto", "Stockholm"), City("SE", "got", "Gothenburg"), City("SE", "mma", "Malmö"),
            City("NL", "ams", "Amsterdam"), City("DE", "fra", "Frankfurt"), City("DE", "ber", "Berlin"),
            City("DE", "dus", "Düsseldorf"), City("CH", "zrh", "Zürich"), City("GB", "lon", "London"),
            City("GB", "man", "Manchester"), City("US", "nyc", "New York, NY"), City("US", "lax", "Los Angeles, CA"),
            City("US", "chi", "Chicago, IL"), City("US", "dal", "Dallas, TX"), City("US", "mia", "Miami, FL"),
            City("US", "sea", "Seattle, WA"), City("US", "atl", "Atlanta, GA"), City("CA", "tor", "Toronto"),
            City("CA", "mtr", "Montréal"), City("FR", "par", "Paris"), City("ES", "mad", "Madrid"),
            City("IT", "mil", "Milan"), City("AT", "vie", "Vienna"), City("NO", "osl", "Oslo"),
            City("DK", "cph", "Copenhagen"), City("FI", "hel", "Helsinki"), City("PL", "waw", "Warsaw"),
            City("RO", "buh", "Bucharest"), City("CZ", "prg", "Prague"), City("JP", "tyo", "Tokyo"),
            City("SG", "sin", "Singapore"), City("AU", "syd", "Sydney"), City("BR", "sao", "São Paulo"),
            City("HK", "hkg", "Hong Kong"), City("ZA", "jnb", "Johannesburg"), City("RS", "beg", "Belgrade"),
            City("IE", "dub", "Dublin"), City("PT", "lis", "Lisbon"), City("TR", "ist", "Istanbul"),
            City("MX", "qro", "Querétaro"),
        )

        /**
         * [count] exits like the core-contract.json fixture, but with a provider URL per server and
         * random datacenter ids and tiers, so that they compress no better than real ones.
         */
        fun exits(count: Int, random: Random = Random(2000)): List<OneExit> = List(count) { i ->
            val city = CITIES[i % CITIES.size]
            val n = i / CITIES.size + 1
            val providerId = "${city.country.lowercase()}-${city.code}-wg-${300 + n}"
            OneExit(
                id = "${city.code.uppercase()}-${n.toString().padStart(3, '0')}",
                countryCode = city.country,
                cityCode = city.code,
                cityName = city.name,
                providerId = providerId,
                providerUrl = "https://mullvad.net/en/servers/$providerId",
                providerName = "Mullvad VPN",
                providerHomepageUrl = "https://mullvad.net",
                datacenterId = random.nextLong(1L shl 32),
                tier = random.nextInt(3),
            )
        }

        /** [count] relays, each with three preferred exits and a certificate of a realistic size. */
        fun relays(count: Int, exits: List<OneExit>, random: Random = Random(300)): List<OneRelay> = List(count) { i ->
            val exit = exits[i % exits.size]
            OneRelay(
                id = "relay-${exit.cityCode}-${i / CITIES.size + 1}",
                countryCode = exit.countryCode,
                cityCode = exit.cityCode,
                cityName = exit.cityName,
                preferredExits = List(3) { RelayPreferredExit(exits[(i + it * CITIES.size) % exits.size].id) },
                ipV4 = List(4) { random.nextInt(1, 255) }.joinToString("."),
                ports = listOf(443, 8443),
                tlsCert = Base64.getEncoder().encodeToString(random.nextBytes(440)),
            )
        }
    }
}
