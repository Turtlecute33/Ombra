use super::{class_cache::ClassCache, tunnel::Tun};
use crate::quicwg::QuicWgConnPacketSender;
use crate::{net::NetworkInterface, network_config::OsNetworkConfig, os::os_trait::Os};
use bytes::Bytes;
use jni::JavaVM;
use std::sync::{Arc, Mutex};
use tokio::sync::watch;

pub struct AndroidOsImpl {
    tun: Mutex<Option<LiveTun>>,
    network_interface: watch::Sender<Option<NetworkInterface>>,
    jvm: Arc<JavaVM>,
    class_cache: Arc<ClassCache>,
}

/// The live TUN and the config that Java built it from.
struct LiveTun {
    /// `None` after `forget_os_network_config`, so the next config goes to Java even when it is the same.
    config: Option<OsNetworkConfig>,
    tun: Tun,
}

impl AndroidOsImpl {
    pub fn new(jvm: Arc<JavaVM>, class_cache: Arc<ClassCache>) -> Self {
        Self { tun: Mutex::new(None), network_interface: watch::channel(None).0, jvm, class_cache }
    }

    pub fn set_network_interface(&self, network_interface: Option<NetworkInterface>) {
        self.network_interface.send_replace(network_interface);
    }

    pub fn jvm(&self) -> Arc<JavaVM> {
        self.jvm.clone()
    }

    pub fn network_interface(&self) -> watch::Receiver<Option<NetworkInterface>> {
        self.network_interface.subscribe()
    }
}

impl Os for AndroidOsImpl {
    async fn set_os_network_config(&self, network_config: OsNetworkConfig, tunnel: QuicWgConnPacketSender) -> Result<(), ()> {
        // Each connect attempt sets the dummy config first, and a failing connect tries again every second. Every
        // `establish()` in Java makes a new interface that replaces the VPN network, so keep the live TUN when the
        // config did not change and give it only the new packet sender.
        let reused_fd = match &*self.tun.lock().unwrap() {
            Some(LiveTun { config: Some(current), tun }) if *current == network_config => tun.try_clone_fd(),
            _ => None,
        };
        let fd = match reused_fd {
            Some(fd) => {
                tracing::info!(message_id = "Hq4tVn8e", "network config unchanged, keeping the TUN device");
                fd
            }
            None => {
                let json = serde_json::to_string(&network_config).map_err(|error| {
                    tracing::error!(message_id = "dK2xNm3q", ?error, "failed to serialize OsNetworkConfig: {error}");
                })?;
                super::ffi::call_set_network_config(self.class_cache.clone(), self.jvm.clone(), json).await?
            }
        };

        let (tun, result) = match Tun::spawn(fd, network_config.mtu, tunnel) {
            Ok(tun) => {
                // Not the config itself: it has the tunnel addresses of this client.
                tracing::info!(message_id = "mLrplF1x", "successfully set network config and spawned TUN device");
                (tun, Ok(()))
            }
            Err(tun) => {
                tracing::error!(message_id = "rS9cUd5v", "failed to spawn TUN reader");
                (tun, Err(()))
            }
        };
        *self.tun.lock().unwrap() = Some(LiveTun { config: Some(network_config), tun });
        result
    }

    async fn unset_os_network_config(&self) -> Result<(), ()> {
        *self.tun.lock().unwrap() = None;
        Ok(())
    }

    fn packet_for_os(&self, packet: Bytes) {
        if let Some(LiveTun { tun, .. }) = &*self.tun.lock().unwrap() {
            tun.write(packet);
        }
    }

    fn forget_os_network_config(&self) {
        if let Some(live_tun) = &mut *self.tun.lock().unwrap() {
            live_tun.config = None;
        }
    }
}
