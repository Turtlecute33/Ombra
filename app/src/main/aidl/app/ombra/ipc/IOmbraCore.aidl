package app.ombra.ipc;

import app.ombra.ipc.IResultCallback;
import app.ombra.ipc.IStateListener;

/**
 * Binder of CoreService (:vpn process). Only callers with our own UID are served.
 * Requests are app-level (app.ombra.ipc.Request as JSON), never raw Rust commands.
 */
interface IOmbraCore {
    /** Runs one Request. The reply goes to cb exactly once. */
    oneway void call(String requestJson, IResultCallback cb);
    /** Registers l and pushes the current STATUS, EXITS, RELAYS, SETTINGS and SYSTEM to it at once. */
    oneway void addListener(IStateListener l);
    oneway void removeListener(IStateListener l);
}
