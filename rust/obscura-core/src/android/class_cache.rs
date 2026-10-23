use jni::{
    JNIEnv,
    objects::{GlobalRef, JClass},
};
use std::sync::Arc;

// Contract with the Kotlin side (app/src/main/java/app/ombra/core/ffi/). R8 must keep these classes and the
// members that Rust uses. The JNI export names in `ffi.rs` follow the same package.
const FFI_HANDLE_CLASS: &str = "app/ombra/core/ffi/Native$FfiHandle";
const ERROR_CODE_EXCEPTION_CLASS: &str = "app/ombra/core/ffi/ErrorCodeException";
const CALLBACKS_CLASS: &str = "app/ombra/core/ffi/Callbacks";

#[derive(Debug)]
pub struct ClassCache {
    ffi_handle: GlobalRef,
    error_code_exception: GlobalRef,
    callbacks: GlobalRef,
}

impl ClassCache {
    /// Looking up app-specific Java classes from native threads isn't possible, so we cache all the app-specific classes we need.
    /// Must be called on a Java thread.
    /// https://developer.android.com/ndk/guides/jni-tips#faq:-why-didnt-findclass-find-my-class
    pub fn new(env: &mut JNIEnv) -> anyhow::Result<Arc<Self>> {
        let ffi_handle = env.find_class(FFI_HANDLE_CLASS)?;
        let ffi_handle = env.new_global_ref(ffi_handle)?;
        let error_code_exception = env.find_class(ERROR_CODE_EXCEPTION_CLASS)?;
        let error_code_exception = env.new_global_ref(error_code_exception)?;
        let callbacks = env.find_class(CALLBACKS_CLASS)?;
        let callbacks = env.new_global_ref(callbacks)?;
        Ok(Arc::new(Self { ffi_handle, error_code_exception, callbacks }))
    }

    pub fn ffi_handle(&self) -> &JClass<'static> {
        self.ffi_handle.as_obj().into()
    }

    pub fn error_code_exception(&self) -> &JClass<'static> {
        self.error_code_exception.as_obj().into()
    }

    /// Static Rust -> Java callbacks: `ffiSetNetworkConfig`, `ffiGetProcessExitReasons`, `ffiStoreWgSecretKey`.
    pub fn callbacks(&self) -> &JClass<'static> {
        self.callbacks.as_obj().into()
    }
}
