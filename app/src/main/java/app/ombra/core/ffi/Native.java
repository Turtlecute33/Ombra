package app.ombra.core.ffi;

import android.app.Application;

import androidx.annotation.Keep;

import java.util.concurrent.CompletableFuture;

/**
 * JNI entry points of libobscuravpn_client.so. The class name, the nested class and every
 * signature are a contract with the Rust fork (docs/DESIGN.md section 4; JNI symbols
 * Java_app_ombra_core_ffi_Native_*). Use {@link RustFfi} from Kotlin.
 */
public final class Native {
    /** Opaque token returned by {@link #initialize}; Rust builds it with the private constructor. */
    @Keep
    public static final class FfiHandle {
        private FfiHandle() {}
    }

    /** Suffix of the only process that may load the core. */
    static final String VPN_PROCESS_SUFFIX = ":vpn";

    private static boolean loaded;

    private Native() {}

    /**
     * Loads the native library once. Two cores in two processes would both write config.json
     * (there is no file lock), so every process except :vpn is refused.
     */
    static synchronized void loadLibrary() {
        String process = Application.getProcessName();
        if (process == null || !process.endsWith(VPN_PROCESS_SUFFIX)) {
            throw new IllegalStateException("The native core may only be loaded in the " + VPN_PROCESS_SUFFIX + " process");
        }
        if (!loaded) {
            System.loadLibrary("obscuravpn_client");
            loaded = true;
        }
    }

    static native FfiHandle initialize(String configDir, String userAgent, byte[] wgSecretKey, byte[] configKey, boolean persistLogs);

    static native void jsonFfi(FfiHandle handle, String json, CompletableFuture<String> future);

    /**
     * The physical network for the tunnel. index must be >= 1. networkHandle is
     * Network.getNetworkHandle(), for binding sockets to that network; mtuHint is the CLAT-aware
     * MTU, or 0 to let Rust read the interface MTU.
     */
    static native void setNetworkInterface(FfiHandle handle, String name, int index, long networkHandle, int mtuHint);

    static native void unsetNetworkInterface(FfiHandle handle);

    static native void wake(FfiHandle handle);

    /** level: 0 TRACE, 1 DEBUG, 2 INFO, 3 WARN, 4 ERROR. Only throwableString may be null. */
    static native void forwardLog(int level, String tag, String message, String messageId, String throwableString);

    static native void flushAndStopPersistedLog();
}
