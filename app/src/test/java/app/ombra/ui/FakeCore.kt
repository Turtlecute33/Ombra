package app.ombra.ui

import app.ombra.core.model.CachedValue
import app.ombra.core.model.ExitList
import app.ombra.core.model.OneRelay
import app.ombra.core.model.Status
import app.ombra.core.model.TrafficStats
import app.ombra.data.CoreRequestException
import app.ombra.data.CoreSource
import app.ombra.ipc.IpcError
import app.ombra.ipc.IpcJson
import app.ombra.ipc.Request
import app.ombra.ipc.Settings
import app.ombra.ipc.SystemState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A [CoreSource] whose state the test sets. It records every request and answers from [answers]
 * or [failures] first; otherwise TrafficStats comes from [traffic], UpdateSettings applies the
 * patch, and everything else answers null.
 */
class FakeCore : CoreSource {
    override val connected = MutableStateFlow(true)
    override val status = MutableStateFlow<Status?>(null)
    override val exits = MutableStateFlow<CachedValue<ExitList>?>(null)
    override val relays = MutableStateFlow<CachedValue<List<OneRelay>>?>(null)
    override val settings = MutableStateFlow<Settings?>(Settings())
    override val system = MutableStateFlow<SystemState?>(null)

    val requests = mutableListOf<Request>()
    val answers = mutableMapOf<Request, String>()

    /** Error code per request. */
    val failures = mutableMapOf<Request, String>()
    val traffic = ArrayDeque<TrafficStats>()

    override fun bind() = Unit

    override fun unbind() = Unit

    override suspend fun request(request: Request): String {
        requests += request
        failures[request]?.let { throw CoreRequestException(it) }
        answers[request]?.let { return it }
        return when (request) {
            Request.TrafficStats -> IpcJson.encodeToString(
                TrafficStats.serializer(),
                traffic.removeFirstOrNull() ?: throw CoreRequestException(IpcError.TIMEOUT),
            )
            is Request.UpdateSettings -> {
                val updated = (settings.value ?: Settings()).applyPatch(request.patch)
                settings.value = updated
                IpcJson.encodeToString(Settings.serializer(), updated)
            }
            else -> "null"
        }
    }
}
