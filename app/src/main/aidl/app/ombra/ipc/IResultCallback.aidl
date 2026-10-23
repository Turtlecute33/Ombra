package app.ombra.ipc;

/** Result of IOmbraCore.call: okJson on success, else errorCode (never both). */
interface IResultCallback {
    oneway void onResult(String okJson, String errorCode);
}
