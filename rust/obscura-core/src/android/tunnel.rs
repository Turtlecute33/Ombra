use crate::{os::packet_buffer::PacketBuffer, quicwg::QuicWgConnPacketSender, rate_limited_log, tokio::AbortOnDrop};
use bytes::Bytes;
use nix::{errno::Errno, unistd};
use std::{
    os::fd::OwnedFd,
    sync::{
        Arc,
        mpsc::{Receiver, SyncSender, TrySendError, sync_channel},
    },
    thread,
    time::Duration,
};
use tokio::io::unix::AsyncFd;

const TUN_MIN_LOG_SILENCE: Duration = Duration::from_secs(5);

/// Packets that wait for the TUN writer thread, about 40 ms at 300 Mbit/s. A full queue drops, as a full NIC queue does.
const TUN_WRITE_QUEUE_LEN: usize = 1024;

pub struct Tun {
    fd: Arc<OwnedFd>,
    /// The MTU of the TUN config: 1280, or `udpMtu` in Speed mode (Ombra patch P5).
    mtu: u16,
    read_loop_task: Option<AbortOnDrop>,
    writer: Option<SyncSender<Bytes>>,
}

impl Tun {
    pub fn spawn(fd: OwnedFd, mtu: u16, tunnel: QuicWgConnPacketSender) -> Result<Self, Self> {
        let fd = Arc::new(fd);
        let fd_watcher = match AsyncFd::new(fd.clone()) {
            Ok(fd_watcher) => fd_watcher,
            Err(error) => {
                tracing::error!(message_id = "sPCZix4J", ?error, "failed to create fd watcher for tun reader: {error}");
                return Err(Self { fd, mtu, read_loop_task: None, writer: None });
            }
        };
        // Reads until the TUN has no packet left or 100 are read, and gives the tunnel the whole batch: one `wg_state`
        // lock and one liveness check per batch instead of per packet (Ombra patch P4, C1).
        let read_loop_task = tokio::spawn(async move {
            let mut packets = PacketBuffer::default();
            loop {
                let mut guard = match fd_watcher.readable().await {
                    Ok(guard) => guard,
                    Err(error) => {
                        tracing::error!(message_id = "r5N6izcO", ?error, "failed to wait for tun to become readable");
                        break;
                    }
                };
                let mut read_error = None;
                while let Some(buffer) = packets.buffer() {
                    match unistd::read(&fd_watcher, &mut buffer[..usize::from(u16::MAX)]) {
                        Ok(0) => break,
                        Ok(n) => packets.commit(u16::try_from(n).expect("read at most u16::MAX bytes")),
                        Err(Errno::EAGAIN) => {
                            guard.clear_ready();
                            break;
                        }
                        Err(error) => {
                            read_error = Some(error);
                            break;
                        }
                    }
                }
                if !packets.is_empty() {
                    tunnel.send(packets.take_iter());
                }
                if let Some(error) = read_error {
                    tracing::error!(message_id = "eagh6Noh", ?error, "failed to read from tun");
                    break;
                }
            }
        });
        let (writer, packets) = sync_channel(TUN_WRITE_QUEUE_LEN);
        let write_fd = fd.clone();
        let writer = thread::Builder::new()
            .name("ombra-tun-tx".into())
            .spawn(move || write_loop(&write_fd, mtu, packets))
            .inspect_err(|error| tracing::error!(message_id = "Lq3vTn8w", ?error, "failed to spawn the TUN writer: {error}"))
            .ok()
            .map(|_| writer);
        Ok(Self { fd, mtu, read_loop_task: Some(read_loop_task.into()), writer })
    }

    /// A second fd for the same TUN device, or `None` when the read loop has stopped (the fd failed).
    pub fn try_clone_fd(&self) -> Option<OwnedFd> {
        if self.read_loop_task.as_ref().is_none_or(|task| task.0.is_finished()) {
            return None;
        }
        self.fd
            .try_clone()
            .inspect_err(|error| tracing::error!(message_id = "Vb7kQe2s", ?error, "failed to duplicate the TUN fd: {error}"))
            .ok()
    }

    /// Queues `packet` for the writer thread, or writes it here when there is no writer thread.
    pub fn write(&self, packet: Bytes) {
        let Some(writer) = &self.writer else {
            return write_packet(&self.fd, self.mtu, &packet);
        };
        match writer.try_send(packet) {
            Ok(()) => {}
            Err(TrySendError::Full(_)) => rate_limited_log!(
                TUN_MIN_LOG_SILENCE,
                tracing::warn!(message_id = "Pq7xUr2k", "TUN write queue full, packet dropped")
            ),
            Err(TrySendError::Disconnected(_)) => rate_limited_log!(
                TUN_MIN_LOG_SILENCE,
                tracing::error!(message_id = "Hv4mWs9e", "TUN writer stopped, packet dropped")
            ),
        }
    }
}

/// Writes the queued packets until the `Tun` is dropped (Ombra patch P10). A TUN write runs the receive path of the inner
/// IP stack in the calling thread. In its own thread it runs at the same time as the receive and the decryption of the
/// next packets: on the phone this doubled the download speed of Speed mode.
fn write_loop(fd: &OwnedFd, mtu: u16, packets: Receiver<Bytes>) {
    super::util::use_normal_scheduling();
    for packet in packets {
        write_packet(fd, mtu, &packet);
    }
}

fn write_packet(fd: &OwnedFd, mtu: u16, packet: &[u8]) {
    if packet.len() > usize::from(mtu) {
        rate_limited_log!(
            TUN_MIN_LOG_SILENCE,
            tracing::warn!(message_id = "Yc1WxQBY", packet_size = packet.len(), "packet larger than MTU")
        );
    }
    if let Err(error) = unistd::write(fd, packet) {
        rate_limited_log!(
            TUN_MIN_LOG_SILENCE,
            tracing::error!(message_id = "W0sOhigq", ?error, "writing packet to tun failed")
        );
    }
}
