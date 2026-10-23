package app.ombra.ipc

import app.ombra.core.model.Status

/**
 * Removes the account number before a Status leaves the :vpn process. Typed on purpose: fields
 * that the model does not know are dropped, so a newer core cannot leak a new secret field.
 */
object StatusSanitizer {
    fun sanitize(status: Status): Status = status.copy(
        accountId = null,
        loggedIn = status.isLoggedIn,
        account = status.account?.let { it.copy(accountInfo = it.accountInfo.copy(id = null)) },
    )

    fun sanitizedJson(status: Status): String = IpcJson.encodeToString(Status.serializer(), sanitize(status))
}
