package app.ombra.vpn

import app.ombra.core.ffi.ErrorCodeException
import app.ombra.core.model.AccountInfo
import app.ombra.core.model.CachedValue
import app.ombra.core.model.CoreCall
import app.ombra.core.model.CoreJson
import app.ombra.core.model.ExitList
import app.ombra.core.model.FreeTrialStatus
import app.ombra.core.model.ManagerCmd
import app.ombra.core.model.OneRelay
import app.ombra.core.model.ServiceStatus
import app.ombra.core.model.Status
import app.ombra.core.model.TrafficStats
import app.ombra.core.model.VpnStatus
import app.ombra.core.model.jsonString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlin.reflect.KClass
import kotlin.time.Duration

/**
 * A scripted Rust core. Answers go through the real decoders of each [CoreCall]. Long-polls wait
 * on the flows below like Rust's watch channels; every other command is recorded in [sent].
 * Login and logout change the status like Rust does.
 */
class FakeCore : CoreChannel {
    val sent = mutableListOf<ManagerCmd>()
    val rawSent = mutableListOf<String>()

    val status = MutableStateFlow<Status?>(null)
    val exits = MutableStateFlow<CachedValue<ExitList>?>(null)
    val relays = MutableStateFlow<CachedValue<List<OneRelay>>?>(null)

    /** Error code per command class. The command still counts as sent. */
    val failures = mutableMapOf<KClass<out ManagerCmd>, String>()

    var debugBundlePath = "/data/cache/debug-archives/bundle.zip"
    val trafficStats = TrafficStats(connectedMs = 1_000, connId = "c1", txBytes = 10, rxBytes = 20, latestLatencyMs = 30)

    private val pollCalls = mutableMapOf<KClass<out ManagerCmd>, Int>()
    private val openPolls = mutableMapOf<KClass<out ManagerCmd>, Int>()
    private var statusVersion = 0

    /** Long-polls of [kind] that wait now. */
    fun openPolls(kind: KClass<out ManagerCmd>): Int = openPolls[kind] ?: 0

    /** Long-polls of [kind] sent so far. */
    fun pollCalls(kind: KClass<out ManagerCmd>): Int = pollCalls[kind] ?: 0

    fun count(kind: KClass<out ManagerCmd>): Int = sent.count { kind.isInstance(it) }

    /** Publishes a new status version, like Rust after any change. */
    fun setStatus(vpnStatus: VpnStatus = VpnStatus.Disconnected, accountId: String? = status.value?.accountId) {
        statusVersion += 1
        status.value = Status(version = "v$statusVersion", vpnStatus = vpnStatus, accountId = accountId)
    }

    override suspend fun <R> call(call: CoreCall<R>): R = call.decode(respond(call.command))

    override suspend fun raw(json: String, timeout: Duration?): String {
        rawSent += json
        return "null"
    }

    private suspend fun respond(command: ManagerCmd): String = when (command) {
        is ManagerCmd.GetStatus -> poll(command, status, command.knownVersion, Status::version, Status.serializer())
        is ManagerCmd.GetExitList -> poll(command, exits, command.knownVersion, { it.version }, EXITS)
        is ManagerCmd.GetRelayList -> poll(command, relays, command.knownVersion, { it.version }, RELAYS)
        else -> answer(command)
    }

    private suspend fun <T : Any> poll(
        command: ManagerCmd,
        flow: MutableStateFlow<T?>,
        known: String?,
        version: (T) -> String,
        serializer: KSerializer<T>,
    ): String {
        pollCalls.merge(command::class, 1, Int::plus)
        failures[command::class]?.let { throw ErrorCodeException(it) }
        openPolls.merge(command::class, 1, Int::plus)
        try {
            return CoreJson.encodeToString(serializer, flow.filterNotNull().first { version(it) != known })
        } finally {
            openPolls.merge(command::class, -1, Int::plus)
        }
    }

    private fun answer(command: ManagerCmd): String {
        sent += command
        failures[command::class]?.let { throw ErrorCodeException(it) }
        return when (command) {
            is ManagerCmd.Login -> "null".also { setStatus(accountId = command.accountId) }
            ManagerCmd.Logout -> "null".also { setStatus(accountId = null) }
            ManagerCmd.ApiGetAccountInfo -> CoreJson.encodeToString(AccountInfo.serializer(), AccountInfo(active = true))
            ManagerCmd.ApiServiceStatus -> CoreJson.encodeToString(ServiceStatus.serializer(), ServiceStatus(FreeTrialStatus(true)))
            ManagerCmd.ApiDeleteAccount -> "{}"
            ManagerCmd.GetTrafficStats -> CoreJson.encodeToString(TrafficStats.serializer(), trafficStats)
            is ManagerCmd.CreateDebugBundle -> jsonString(debugBundlePath)
            else -> "null"
        }
    }

    private companion object {
        val EXITS: KSerializer<CachedValue<ExitList>> = CachedValue.serializer(ExitList.serializer())
        val RELAYS: KSerializer<CachedValue<List<OneRelay>>> = CachedValue.serializer(ListSerializer(OneRelay.serializer()))
    }
}
