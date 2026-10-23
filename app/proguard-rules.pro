# JNI contract with the Rust core (docs/DESIGN.md section 4). Rust binds the natives by the class
# name and looks up these classes and members with FindClass / GetMethodID. A rename or a removed
# member aborts the :vpn process at initialize or at the first callback.
-keep class app.ombra.core.ffi.Native {
    static native <methods>;
}
-keep class app.ombra.core.ffi.Native$FfiHandle {
    <init>();
}
-keep class app.ombra.core.ffi.ErrorCodeException {
    <init>(java.lang.String);
}
-keep class app.ombra.core.ffi.Callbacks {
    public static int ffiSetNetworkConfig(java.lang.String);
    public static java.lang.String ffiGetProcessExitReasons();
    public static boolean ffiStoreWgSecretKey(byte[]);
}

# Release builds keep no verbose, debug or info logging.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
