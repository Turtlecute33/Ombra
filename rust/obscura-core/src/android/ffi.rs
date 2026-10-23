//! JNI exports for the static natives of `app.ombra.core.ffi.Native` (Ombra docs/DESIGN.md section 4).
//! Symbol names and argument types are a contract with the Kotlin side.

use super::{
    class_cache::ClassCache,
    future::signal_json_ffi_future,
    os_impl::AndroidOsImpl,
    util::{Utf8JavaStr, throw_runtime_exception},
};
use crate::wg_key_store::{KeychainSetSecretKeyFn, WgKeyStore};
use crate::{
    manager::Manager,
    manager_cmd::{ManagerCmd, redacted_json_error},
    net::NetworkInterface,
    positive_u31::PositiveU31,
};
use anyhow::Context as _;
use jni::{
    JNIEnv, JavaVM,
    objects::{JByteArray, JClass, JObject, JString, JValue},
    sys::{JNI_FALSE, jboolean, jint, jlong, jobject},
};
use std::{
    ffi::c_void,
    os::fd::{FromRawFd as _, OwnedFd},
    sync::Arc,
    sync::OnceLock,
};
use tokio::runtime::Runtime;
use zeroize::Zeroizing;

pub struct Global {
    pub manager: Arc<Manager>,
    pub os_impl: Arc<AndroidOsImpl>,
    pub class_cache: Arc<ClassCache>,
    pub runtime: Runtime,
}

static GLOBAL: OnceLock<Global> = OnceLock::new();

/// Get global from handle.
///
/// Note: The handle is not actually used, just proof of construction. We rely on the Java type of FFI functions to enforce its existence.
fn global_from_handle(_handle: &JObject) -> &'static Global {
    GLOBAL.get().expect("global not initialized")
}

const RUST_LOG_DIR_NAME: &str = "rust-log";

/// One worker thread (Ombra patch P10). The tasks of the data path give each packet to the next task. On one thread
/// that needs no wakeup of another thread, and the phone keeps the busy thread on a fast core. On the phone, every mode
/// was fastest with one worker and used about half the CPU of four (`docs/research/device-test.md`). A task must not
/// block: blocking work goes to `spawn_blocking`, and the TUN writes have their own thread (`tunnel.rs`).
const RUNTIME_WORKER_THREADS: usize = 1;

/// Local refs for one Rust -> Java call on a thread that Rust attached. Such a thread never returns to Java, so its
/// local refs are freed only with a local frame (or when the thread ends).
const LOCAL_FRAME_CAPACITY: i32 = 4;

/// cbindgen:ignore
#[unsafe(no_mangle)]
pub extern "C" fn JNI_OnLoad(_vm: *mut jni::sys::JavaVM, _reserved: *mut c_void) -> jint {
    jni::sys::JNI_VERSION_1_6
}

fn initialize(
    env: &mut JNIEnv,
    j_config_dir: &JString,
    j_user_agent: &JString,
    j_wg_secret_key: &JByteArray,
    persist_logs: bool,
    class_cache: Arc<ClassCache>,
) -> anyhow::Result<Global> {
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(RUNTIME_WORKER_THREADS)
        .on_thread_start(super::util::use_normal_scheduling)
        .enable_all()
        .build()
        .expect("Failed to create tokio runtime");
    let _runtime_guard = runtime.enter();
    let config_dir = Utf8JavaStr::new(env, j_config_dir, "j_config_dir", "INsGbyhM")?;
    let user_agent = Utf8JavaStr::new(env, j_user_agent, "j_user_agent", "NXCS11u3")?;
    // Without persistence no log file or log dir is created. `persistLogs` is the Diagnostics setting, which also
    // shows the 1 Hz data path stats in logcat (never in the files).
    let log_dir = persist_logs.then(|| config_dir.as_path().join(RUST_LOG_DIR_NAME));
    let log_persistence = crate::logging::init(tracing_android::layer("ObscuraNative")?, log_dir.as_deref(), persist_logs);
    tracing::info!(message_id = "GBtOgXur", persist_logs, "log persistence selected by Java");
    rustls::crypto::aws_lc_rs::default_provider()
        .install_default()
        .map_err(|_| anyhow::format_err!("failed to install crypto provider"))?;
    let jvm = Arc::new(env.get_java_vm().context("failed to get JavaVM")?);
    // The secret key never goes into config.json: Java keeps it encrypted with an Android Keystore key, gives it
    // back here at start, and stores every new key through `ffiStoreWgSecretKey`.
    let wg_key_store = WgKeyStore::Keychain {
        secret_key: wg_secret_key_from_java(env, j_wg_secret_key),
        set_secret_key: store_wg_secret_key_fn(jvm.clone(), class_cache.clone()),
    };
    let os_impl = Arc::new(AndroidOsImpl::new(jvm, class_cache.clone()));
    let manager = Manager::new(
        config_dir.as_path().into(),
        wg_key_store,
        user_agent.as_str().into(),
        os_impl.clone(),
        os_impl.network_interface(),
        log_persistence,
        true,
    )?;
    Ok(Global { manager, os_impl, runtime, class_cache })
}

/// `null` or an empty array means that Java has no stored key. A wrong length is left to the key store, which
/// logs it and makes a new key.
fn wg_secret_key_from_java(env: &mut JNIEnv, j_wg_secret_key: &JByteArray) -> Option<Zeroizing<Vec<u8>>> {
    if j_wg_secret_key.is_null() {
        return None;
    }
    match env.convert_byte_array(j_wg_secret_key) {
        Ok(secret_key) => Some(Zeroizing::new(secret_key)).filter(|secret_key| !secret_key.is_empty()),
        Err(error) => {
            clear_pending_exception(env);
            tracing::error!(message_id = "uvJe81GP", ?error, "failed to read wireguard secret key from Java: {error}");
            None
        }
    }
}

/// `WgKeyStore::Keychain` callback that calls `Callbacks.ffiStoreWgSecretKey(byte[]): boolean`.
///
/// Rust calls it on a tokio worker while it holds the client state, so Java must not wait for Rust in it. The
/// thread stays attached as a daemon, like the other callbacks.
fn store_wg_secret_key_fn(jvm: Arc<JavaVM>, class_cache: Arc<ClassCache>) -> KeychainSetSecretKeyFn {
    Box::new(move |secret_key: &[u8; 32]| {
        let mut env = match jvm.attach_current_thread_as_daemon() {
            Ok(env) => env,
            Err(error) => {
                tracing::error!(message_id = "JydbqyGe", ?error, "failed to attach thread to JVM: {error}");
                return false;
            }
        };
        let stored = env.with_local_frame(LOCAL_FRAME_CAPACITY, |env| -> jni::errors::Result<bool> {
            let j_secret_key = env.byte_array_from_slice(secret_key)?;
            let stored = env
                .call_static_method(class_cache.callbacks(), "ffiStoreWgSecretKey", "([B)Z", &[JValue::Object(&j_secret_key)])
                .and_then(|value| value.z());
            clear_pending_exception(env);
            // Java must not keep the array: wipe the copy of the key in the Java heap.
            env.set_byte_array_region(&j_secret_key, 0, &[0; 32])?;
            stored
        });
        stored.unwrap_or_else(|error| {
            clear_pending_exception(&mut env);
            tracing::error!(message_id = "AlXzOuep", ?error, "failed to call ffiStoreWgSecretKey: {error}");
            false
        })
    })
}

/// A Java exception left pending on a thread that stays attached would make every later JNI call on that thread
/// fail. The caller logs the error that jni-rs returned.
fn clear_pending_exception(env: &mut JNIEnv) {
    if env.exception_check().unwrap_or(true) {
        let _ = env.exception_clear();
    }
}

/// cbindgen:ignore
/// Must be called on a Java thread.
/// `wgSecretKey` may be `null`. `persistLogs == false` writes no log files.
#[unsafe(no_mangle)]
pub extern "C" fn Java_app_ombra_core_ffi_Native_initialize(
    mut env: JNIEnv,
    _: JClass,
    j_config_dir: JString,
    j_user_agent: JString,
    j_wg_secret_key: JByteArray,
    j_persist_logs: jboolean,
) -> jobject {
    tracing::info!(message_id = "PRXlxa85", "starting ffi initialization");
    let mut first_init = false;
    let global = GLOBAL.get_or_init(|| {
        first_init = true;
        let class_cache = ClassCache::new(&mut env).expect("creating class cache failed");
        let persist_logs = j_persist_logs != JNI_FALSE;
        let global = initialize(&mut env, &j_config_dir, &j_user_agent, &j_wg_secret_key, persist_logs, class_cache).expect("`initialize` failed");
        tracing::info!(message_id = "Y6cNkZXW", "ffi initialized");
        global
    });
    if !first_init {
        tracing::error!(message_id = "sxsEyRKH", "ffi was already initialized")
    }
    env.new_object(global.class_cache.ffi_handle(), "()V", &[])
        .expect("failed to create FfiHandle")
        .into_raw()
}

fn json_ffi(global: &'static Global, env: &mut JNIEnv, j_json_cmd: &JString, j_future: &JObject) -> anyhow::Result<()> {
    let json_cmd = Utf8JavaStr::new(env, j_json_cmd, "j_json_cmd", "3ZxXYd09")?;
    // Java gets the error message too, so it must not quote the command (a login has the account number).
    let cmd = serde_json::from_str::<ManagerCmd>(json_cmd.as_str())
        .map_err(|error| anyhow::format_err!("invalid command JSON: {}", redacted_json_error(&error)))?;
    // This extends the Java object's lifetime until dropped.
    let j_future = env.new_global_ref(j_future)?;
    let _runtime_guard = global.runtime.enter();
    let jvm = env.get_java_vm()?;
    tokio::spawn(async move {
        let result = cmd.run(&global.manager, None).await;
        // This attaches the current thread to the JVM for the entire life of
        // the thread, which is significantly more performant than
        // attaching/detaching on each use. This will be a no-op if already
        // attached.
        //
        // Since it's attached as a "daemon thread", the life of this thread
        // won't extend the life of the JVM.
        match jvm.attach_current_thread_as_daemon() {
            Ok(mut env) => {
                // Without the frame the result string or exception of each command would stay on the worker.
                let signaled = env.with_local_frame(LOCAL_FRAME_CAPACITY, |env| {
                    signal_json_ffi_future(&global.class_cache, env, j_future.as_obj(), result)
                });
                if let Err(error) = signaled {
                    clear_pending_exception(&mut env);
                    tracing::error!(message_id = "OY0SMEhn", ?error, "failed to signal Java future");
                }
            }
            Err(error) => {
                tracing::error!(message_id = "Wg0053Pz", ?error, "failed to attach thread to JVM");
                // We can't interact with the JVM to throw an exception or
                // call methods on the Java future, so we have to give up.
            }
        };
    });
    Ok(())
}

/// cbindgen:ignore
#[unsafe(no_mangle)]
pub extern "C" fn Java_app_ombra_core_ffi_Native_jsonFfi(mut env: JNIEnv, _: JClass, handle: JObject, j_json_cmd: JString, j_future: JObject) {
    let global = global_from_handle(&handle);
    if let Err(error) = json_ffi(global, &mut env, &j_json_cmd, &j_future) {
        tracing::error!(message_id = "jmx2DBFz", ?error, "`json_ffi` failed");
        throw_runtime_exception(&mut env, error);
    }
}

fn set_network_interface(
    env: &mut JNIEnv,
    global: &'static Global,
    j_name: &JString,
    j_index: jint,
    j_network_handle: jlong,
    j_mtu_hint: jint,
) -> anyhow::Result<()> {
    let name = Utf8JavaStr::new(env, j_name, "j_name", "Quz8O0qu")?.to_string();
    let index = u32::try_from(j_index).and_then(PositiveU31::try_from).with_context(|| {
        tracing::error!(message_id = "qvDcd36g", "network interface index wasn't a positive u32");
        "network interface index wasn't a positive u32"
    })?;
    // `Network.getNetworkHandle()` is never negative. 0 leaves the sockets unbound.
    let network_handle = u64::try_from(j_network_handle).unwrap_or_else(|_| {
        tracing::error!(message_id = "Tg7nWc2K", "negative network handle, sockets stay unbound");
        0
    });
    // 0 means unknown: Rust reads the interface MTU.
    let mtu_hint = u16::try_from(j_mtu_hint).ok().filter(|mtu| *mtu > 0);
    global
        .os_impl
        .set_network_interface(Some(NetworkInterface { name, index, network_handle, mtu_hint }));
    Ok(())
}

/// cbindgen:ignore
/// Java: `setNetworkInterface(FfiHandle handle, String name, int index, long networkHandle, int mtuHint)`. `index >= 1`;
/// `networkHandle` is `Network.getNetworkHandle()`, 0 for none; `mtuHint` is the CLAT-aware MTU, 0 for unknown.
#[unsafe(no_mangle)]
pub extern "C" fn Java_app_ombra_core_ffi_Native_setNetworkInterface(
    mut env: JNIEnv,
    _: JClass,
    handle: JObject,
    j_name: JString,
    j_index: jint,
    j_network_handle: jlong,
    j_mtu_hint: jint,
) {
    let global = global_from_handle(&handle);
    if let Err(error) = set_network_interface(&mut env, global, &j_name, j_index, j_network_handle, j_mtu_hint) {
        tracing::error!(message_id = "OOorBpQJ", ?error, "failed to set network interface: {error}");
    }
}

/// cbindgen:ignore
#[unsafe(no_mangle)]
pub extern "C" fn Java_app_ombra_core_ffi_Native_unsetNetworkInterface(_env: JNIEnv, _: JClass, handle: JObject) {
    let global = global_from_handle(&handle);
    global.os_impl.set_network_interface(None)
}

/// cbindgen:ignore
/// Call when the device wakes (screen on, Doze exit). It sends a liveness probe at once, and the tunnel counts
/// as dead after 2 lost probes instead of 5, so a tunnel that died in sleep reconnects sooner. No-op when there
/// is no connection.
#[unsafe(no_mangle)]
pub extern "C" fn Java_app_ombra_core_ffi_Native_wake(_env: JNIEnv, _: JClass, handle: JObject) {
    let global = global_from_handle(&handle);
    let _runtime_guard = global.runtime.enter();
    global.manager.wake();
}

/// cbindgen:ignore
#[unsafe(no_mangle)]
pub extern "C" fn Java_app_ombra_core_ffi_Native_flushAndStopPersistedLog(_env: JNIEnv, _: JClass) {
    tracing::info!(message_id = "nR7wFqLm", "flushing and stopping log persistence on Java request");
    crate::logging::flush_and_stop_persisted_log();
}

// We'd need to use `getStackTrace` to get more information than this, but that
// seems relatively expensive, has a fiddly API, and still isn't exactly what we
// want (i.e. line numbers are for `return` statements).
fn forward_log(
    env: &mut JNIEnv,
    j_level: jint,
    j_tag: &JString,
    j_message: &JString,
    j_message_id: &JString,
    j_throwable_string: &JString,
) -> anyhow::Result<()> {
    let tag = Utf8JavaStr::new(env, j_tag, "j_tag", "Nfw9yJpe")?;
    let tag = tag.as_str();
    let message = Utf8JavaStr::new(env, j_message, "j_message", "gOpofoUs")?;
    let message = message.as_str();
    let message_id = Utf8JavaStr::new(env, j_message_id, "j_message_id", "vYRZ3DPv")?;
    let message_id = message_id.as_str();
    let throwable_string = Utf8JavaStr::from_nullable(env, j_throwable_string, "j_throwable_string", "5BNd8Tn1")?;
    let throwable_string = throwable_string.as_ref().map(Utf8JavaStr::as_str);
    // https://github.com/tokio-rs/tracing/issues/372
    match j_level {
        0 => tracing::event!(target: "java", tracing::Level::TRACE, message_id, tag, throwable_string, message),
        1 => tracing::event!(target: "java", tracing::Level::DEBUG, message_id, tag, throwable_string, message),
        2 => tracing::event!(target: "java", tracing::Level::INFO, message_id, tag, throwable_string, message),
        3 => tracing::event!(target: "java", tracing::Level::WARN, message_id, tag, throwable_string, message),
        4 => tracing::event!(target: "java", tracing::Level::ERROR, message_id, tag, throwable_string, message),
        _ => anyhow::bail!("invalid log level: {j_level}"),
    }
    Ok(())
}

/// cbindgen:ignore
#[unsafe(no_mangle)]
pub extern "C" fn Java_app_ombra_core_ffi_Native_forwardLog(
    mut env: JNIEnv,
    _: JClass,
    j_level: jint,
    j_tag: JString,
    j_message: JString,
    j_message_id: JString,
    j_throwable_string: JString,
) {
    if let Err(error) = forward_log(&mut env, j_level, &j_tag, &j_message, &j_message_id, &j_throwable_string) {
        tracing::error!(message_id = "Cgb1qGM7", ?error, "failed to forward Java logging");
    }
}

pub(super) async fn call_set_network_config(class_cache: Arc<ClassCache>, jvm: Arc<JavaVM>, json: String) -> Result<OwnedFd, ()> {
    tokio::task::spawn_blocking(move || {
        let mut env = jvm
            .attach_current_thread_as_daemon()
            .map_err(|error| tracing::error!(message_id = "c5B2cENp", ?error, "failed to attach thread to JVM: {error}"))?;
        let j_fd = in_local_frame(&mut env, |env| {
            let json_str = match env.new_string(json) {
                Ok(json_str) => json_str,
                Err(error) => {
                    clear_pending_exception(env);
                    tracing::error!(message_id = "H6mOZNvn", ?error, "failed to create JNI string: {error}");
                    return Err(());
                }
            };
            let j_fd = env.call_static_method(
                class_cache.callbacks(),
                "ffiSetNetworkConfig",
                "(Ljava/lang/String;)I",
                &[JValue::Object(&json_str.into())],
            );
            clear_pending_exception(env);
            j_fd.map_err(|error| tracing::error!(message_id = "oP7aSb3t", ?error, "failed to call ffiSetNetworkConfig: {error}"))?
                .i()
                .map_err(|error| tracing::error!(message_id = "orT0EU1k", ?error, "ffiSetNetworkConfig did not return an int: {error}"))
        })??;
        if j_fd >= 0 {
            // SAFETY: `detachFd` surrendered ownership of the FD on the Kotlin side. No cleanup required besides `close`.
            Ok(unsafe { OwnedFd::from_raw_fd(j_fd) })
        } else {
            Err(())
        }
    })
    .await
    .expect("spawn_blocking panicked")
}

pub(crate) fn process_exit_reasons_json() -> Option<String> {
    let Some(global) = GLOBAL.get() else {
        tracing::error!(message_id = "sQ4wPzXn", "global not initialized");
        return None;
    };
    let jvm = global.os_impl.jvm();
    let mut env = jvm
        .attach_current_thread_as_daemon()
        .map_err(|error| tracing::error!(message_id = "aY8dRk2J", ?error, "failed to attach thread to JVM: {error}"))
        .ok()?;
    in_local_frame(&mut env, |env| {
        let result = env.call_static_method(global.class_cache.callbacks(), "ffiGetProcessExitReasons", "()Ljava/lang/String;", &[]);
        clear_pending_exception(env);
        let result = result
            .map_err(|error| tracing::error!(message_id = "Gv5tBm0e", ?error, "failed to call ffiGetProcessExitReasons: {error}"))
            .ok()?;
        let obj = match result.l() {
            Ok(obj) if !obj.is_null() => JString::from(obj),
            result => {
                tracing::error!(message_id = "wN7cLq9i", ?result, "ffiGetProcessExitReasons did not return a string");
                return None;
            }
        };
        let json = env
            .get_string(&obj)
            .map_err(|error| {
                tracing::error!(
                    message_id = "pE3hUf6s",
                    ?error,
                    "ffiGetProcessExitReasons result wasn't a String: {error}"
                )
            })
            .ok()?;
        Some(String::from(json))
    })
    .ok()
    .flatten()
}

/// Runs `f` in a local frame (see `LOCAL_FRAME_CAPACITY`). Fails only when the frame cannot be made, which is logged.
fn in_local_frame<T>(env: &mut JNIEnv, f: impl FnOnce(&mut JNIEnv) -> T) -> Result<T, ()> {
    env.with_local_frame(LOCAL_FRAME_CAPACITY, |env| Ok::<_, jni::errors::Error>(f(env)))
        .map_err(|error| {
            clear_pending_exception(env);
            tracing::error!(message_id = "Lf4qNw7Z", ?error, "failed to push a JNI local frame: {error}");
        })
}
