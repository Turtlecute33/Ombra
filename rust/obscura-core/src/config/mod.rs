mod persistence;
mod sealed;

pub mod cached;
pub(crate) mod dns_cache;
pub mod feature_flags;
#[cfg(test)]
mod persistence_test;

use crate::errors::ConfigDirty;
use crate::wg_key_store::WgKeyStore;
pub use persistence::*;
pub use sealed::ConfigKey;
use std::path::PathBuf;
use std::sync::{Arc, Condvar, Mutex};
use std::thread::{self, JoinHandle};

/// Writes `config.json` on its own thread, so that the JSON, the encryption and the `fdatasync` do not stop the one
/// tokio worker of the data path (Android). Only the newest config waits: a newer one replaces an older one that is
/// not written yet.
struct ConfigSaver {
    state: Arc<(Mutex<SaverState>, Condvar)>,
    /// `None` when the thread could not start: then each save runs in place.
    thread: Option<JoinHandle<()>>,
}

struct SaverState {
    pending: Option<Config>,
    in_flight: bool,
    /// The last write failed, so the file is older than the config in memory.
    failed: bool,
    shutdown: bool,
}

impl ConfigSaver {
    fn new(config_dir: PathBuf, key: Option<Arc<ConfigKey>>) -> Self {
        let state = Arc::new((
            Mutex::new(SaverState { pending: None, in_flight: false, failed: false, shutdown: false }),
            Condvar::new(),
        ));
        let worker_state = state.clone();
        let thread = thread::Builder::new()
            .name("ombra-cfg-save".into())
            .spawn(move || {
                let (lock, written) = &*worker_state;
                loop {
                    let config = {
                        let mut state = written
                            .wait_while(lock.lock().unwrap(), |state| state.pending.is_none() && !state.shutdown)
                            .unwrap();
                        // A config that waits is written before the thread stops.
                        let Some(config) = state.pending.take() else { break };
                        state.in_flight = true;
                        config
                    };
                    let failed = !save_logged(&config_dir, &config, key.as_deref());
                    let mut state = lock.lock().unwrap();
                    state.in_flight = false;
                    // A newer config that waits decides the state when it is written.
                    if state.pending.is_none() {
                        state.failed = failed;
                    }
                    written.notify_all();
                }
            })
            .inspect_err(|error| tracing::error!(message_id = "Wc5nPq2L", ?error, "failed to spawn the config writer: {error}"))
            .ok();
        Self { state, thread }
    }

    /// Gives `config` to the thread. False when there is no thread.
    fn schedule(&self, config: Config) -> bool {
        if self.thread.is_none() {
            return false;
        }
        let (lock, written) = &*self.state;
        lock.lock().unwrap().pending = Some(config);
        written.notify_all();
        true
    }

    /// Waits until the thread wrote all configs. True when the last write failed.
    fn wait_idle(&self) -> bool {
        let (lock, written) = &*self.state;
        written
            .wait_while(lock.lock().unwrap(), |state| state.pending.is_some() || state.in_flight)
            .unwrap()
            .failed
    }

    fn failed(&self) -> bool {
        self.state.0.lock().unwrap().failed
    }

    fn set_failed(&self, failed: bool) {
        self.state.0.lock().unwrap().failed = failed;
    }
}

impl Drop for ConfigSaver {
    fn drop(&mut self) {
        let (lock, written) = &*self.state;
        lock.lock().unwrap().shutdown = true;
        written.notify_all();
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

/// Config save errors are usually not recoverable and don't influence desired behavior, so we try, log and move on
/// without returning the error. The next change tries again (`SaverState::failed`).
fn save_logged(config_dir: &std::path::Path, config: &Config, key: Option<&ConfigKey>) -> bool {
    save_with_key(config_dir, config, key)
        .inspect_err(|error| tracing::error!(message_id = "C4t7uMUX", ?error, "error saving config: {error}"))
        .is_ok()
}

pub struct ConfigHandle {
    config_dir: PathBuf,
    config: Config,
    /// Encrypts the file (Ombra patch P13). `None`: plain text, as upstream.
    key: Option<Arc<ConfigKey>>,
    saver: ConfigSaver,
}

impl ConfigHandle {
    pub fn new(config_dir: PathBuf, key_store: &WgKeyStore, key: Option<ConfigKey>) -> Result<Self, ConfigLoadError> {
        let key = key.map(Arc::new);
        let mut config = load_with_key(&config_dir, key_store, key.as_deref())?;
        config.migrate();
        let saver = ConfigSaver::new(config_dir.clone(), key.clone());
        Ok(Self { config_dir, config, key, saver })
    }

    /// Changes the config. The writer thread writes it soon after, when it changed or when the last write failed.
    pub fn change<T>(&mut self, f: impl FnOnce(&mut Config) -> T) -> T {
        let mut new_config = self.config.clone();
        let ret = f(&mut new_config);
        if (self.config != new_config || self.saver.failed()) && !self.saver.schedule(new_config.clone()) {
            self.saver.set_failed(!save_logged(&self.config_dir, &new_config, self.key.as_deref()));
        }
        self.config = new_config;
        ret
    }

    /// [`Self::change`], but the config is on the disk when this returns: for an account change, and for the id of a
    /// Speed mode tunnel before the API makes the tunnel.
    pub fn change_sync<T>(&mut self, f: impl FnOnce(&mut Config) -> T) -> T {
        let mut new_config = self.config.clone();
        let ret = f(&mut new_config);
        let failed = self.saver.wait_idle();
        if failed || self.config != new_config {
            self.saver.set_failed(!save_logged(&self.config_dir, &new_config, self.key.as_deref()));
        }
        self.config = new_config;
        ret
    }

    pub fn delete_backups(&self) {
        persistence::delete_backups(&self.config_dir)
    }

    /// Waits for the writer thread, then tells if the file has the config in memory.
    pub fn check_persisted(&self) -> Result<(), ConfigDirty> {
        (!self.saver.wait_idle()).then_some(()).ok_or(ConfigDirty)
    }
}

impl std::ops::Deref for ConfigHandle {
    type Target = Config;

    fn deref(&self) -> &Self::Target {
        &self.config
    }
}
