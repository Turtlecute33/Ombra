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

pub struct ConfigHandle {
    config_dir: PathBuf,
    config: Config,
    dirty: bool,
    /// Encrypts the file (Ombra patch P13). `None`: plain text, as upstream.
    key: Option<ConfigKey>,
}

impl ConfigHandle {
    pub fn new(config_dir: PathBuf, key_store: &WgKeyStore, key: Option<ConfigKey>) -> Result<Self, ConfigLoadError> {
        let mut config = load_with_key(&config_dir, key_store, key.as_ref())?;
        config.migrate();
        Ok(Self { dirty: false, config_dir, config, key })
    }
    pub fn change<T>(&mut self, f: impl FnOnce(&mut Config) -> T) -> T {
        let mut new_config = self.config.clone();
        let ret = f(&mut new_config);
        self.dirty |= self.config != new_config;
        if self.dirty {
            // Config save errors are usually not recoverable and don't influence desired behavior, so we try, log and move on without returning the error
            match save_with_key(&self.config_dir, &new_config, self.key.as_ref()) {
                Ok(_) => self.dirty = false,
                Err(error) => tracing::error!(message_id = "C4t7uMUX", ?error, "error saving config: {error}"),
            }
        }
        self.config = new_config;
        ret
    }

    pub fn delete_backups(&self) {
        persistence::delete_backups(&self.config_dir)
    }

    pub fn check_persisted(&self) -> Result<(), ConfigDirty> {
        (!self.dirty).then_some(()).ok_or(ConfigDirty)
    }
}

impl std::ops::Deref for ConfigHandle {
    type Target = Config;

    fn deref(&self) -> &Self::Target {
        &self.config
    }
}
