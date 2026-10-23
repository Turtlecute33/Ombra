package app.ombra.data

import app.ombra.core.model.ServiceStatus
import app.ombra.core.model.TrafficStats
import app.ombra.core.model.parseJsonString
import app.ombra.ipc.IpcError
import app.ombra.ipc.IpcJson
import app.ombra.ipc.Request
import kotlinx.serialization.DeserializationStrategy

// Typed wrappers of CoreSource.request. Each throws CoreRequestException.

suspend fun CoreSource.trafficStats(): TrafficStats = decode(TrafficStats.serializer(), request(Request.TrafficStats))

/** Free trial availability. Calls the Obscura API: only on user action. */
suspend fun CoreSource.serviceStatus(): ServiceStatus = decode(ServiceStatus.serializer(), request(Request.ServiceStatus))

/** Creates and logs in to a new account; returns its number. Calls the Obscura API. */
suspend fun CoreSource.createAccount(): String =
    stringResult(request(Request.CreateAccount)) ?: throw CoreRequestException(IpcError.BAD_RESPONSE)

/** The full account number, or null when logged out. Only on explicit user action. */
suspend fun CoreSource.revealAccountNumber(): String? = stringResult(request(Request.RevealAccountNumber))

/**
 * Creates a debug bundle and returns the absolute path of the zip. Only after the consent screen:
 * the core probes the network and collects logs.
 */
suspend fun CoreSource.createDebugBundle(feedback: String?): String =
    stringResult(request(Request.CreateDebugBundle(feedback))) ?: throw CoreRequestException(IpcError.BAD_RESPONSE)

/** A result that is one JSON string or null. */
private fun stringResult(json: String): String? = try {
    parseJsonString(json)
} catch (e: IllegalArgumentException) {
    throw CoreRequestException(IpcError.BAD_RESPONSE)
}

internal fun <T> decode(deserializer: DeserializationStrategy<T>, json: String): T = try {
    IpcJson.decodeFromString(deserializer, json)
} catch (e: IllegalArgumentException) {
    // SerializationException is an IllegalArgumentException.
    throw CoreRequestException(IpcError.BAD_RESPONSE)
}
