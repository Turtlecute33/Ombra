package app.ombra.ipc;

/**
 * Receives state pushes from :vpn. kind is one of app.ombra.ipc.StateKind. Short JSON comes as a
 * String, longer JSON gzip-compressed (app.ombra.ipc.StatePush).
 */
interface IStateListener {
    oneway void onState(int kind, String json);
    oneway void onStateGz(int kind, in byte[] gzJson);
}
