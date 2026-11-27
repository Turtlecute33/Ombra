use crate::{os::packet_buffer::PacketBuffer, quicwg::QuicWgConnPacketSender, rate_limited_log, tokio::AbortOnDrop};
use bytes::Bytes;
use nix::{errno::Errno, unistd};
use std::{
    os::fd::OwnedFd,
    sync::{
        Arc, Mutex,
        atomic::{AtomicUsize, Ordering},
        mpsc::{Receiver, SyncSender, TrySendError, sync_channel},
    },
    thread,
    time::Duration,
};
use tokio::io::unix::AsyncFd;

const TUN_MIN_LOG_SILENCE: Duration = Duration::from_secs(5);

/// Packets that wait for the TUN writer thread. A full queue drops, as a full NIC queue does. The queue is empty most
/// of the time: the writer is faster than the downlink. It fills when the scheduler holds the writer back (single
/// writes took up to 15 ms on the phone) or when a burst comes in. With 1024 packets, a download at 350 Mbit/s lost
/// 2500 to 7000 packets in 10 s here. The longest queue was about 3700 packets, and with 4096 the same downloads lost
/// almost none and ran at about 580 against 400 Mbit/s, with about the same ping (Ombra patch P25, device test 2026-11-10).
const TUN_WRITE_QUEUE_LEN: usize = 4096;

/// One packet, or the packets of one receive batch: one channel send and one wakeup of the writer for each batch.
enum TunWriteItem {
    One(Bytes),
    Batch(Vec<Bytes>),
}

impl TunWriteItem {
    fn packet_count(&self) -> usize {
        match self {
            Self::One(_) => 1,
            Self::Batch(batch) => batch.len(),
        }
    }
}

pub struct Tun {
    fd: Arc<OwnedFd>,
    /// The MTU of the TUN config: 1280, or `udpMtu` in Speed mode (Ombra patch P5).
    mtu: u16,
    /// The reader gives its packets to this tunnel. A new tunnel replaces it in place (`set_tunnel`).
    tunnel: Arc<Mutex<QuicWgConnPacketSender>>,
    read_loop_task: Option<AbortOnDrop>,
    writer: Option<SyncSender<TunWriteItem>>,
    /// The packets in the writer queue. The limit is in packets, also when an item is a batch.
    queued_packets: Arc<AtomicUsize>,
}

impl Tun {
    pub fn spawn(fd: OwnedFd, mtu: u16, tunnel: QuicWgConnPacketSender) -> Result<Self, Self> {
        let fd = Arc::new(fd);
        let tunnel = Arc::new(Mutex::new(tunnel));
        let queued_packets = Arc::new(AtomicUsize::new(0));
        // SAFETY: the `AsyncFd` owns a clone of the `Arc`, so the fd stays open and refers to the same file for the whole
        // life of the `AsyncFd`, and `as_raw_fd` gives the same fd each time. `AsyncFd::new` is deprecated for this
        // contract (tokio PR 8587).
        let fd_watcher = match unsafe { AsyncFd::register(fd.clone()) } {
            Ok(fd_watcher) => fd_watcher,
            Err(error) => {
                tracing::error!(message_id = "sPCZix4J", ?error, "failed to create fd watcher for tun reader: {error}");
                return Err(Self { fd, mtu, tunnel, read_loop_task: None, writer: None, queued_packets });
            }
        };
        // Reads until the TUN has no packet left or 100 are read, and gives the tunnel the whole batch: one `wg_state`
        // lock and one liveness check per batch instead of per packet (Ombra patch P4, C1).
        //
        // After a full batch the TUN stays readable, so this task would read the next batch at once. quinn sends at
        // most 20 datagrams each time its connection task runs, and its send queue (256 KiB) holds less than two
        // batches: the queue then dropped the oldest packets. On the phone, these drops were most of the losses of an
        // upload. The yield lets the other tasks run first, also the one that sends (Ombra patch P24).
        let read_tunnel = tunnel.clone();
        let read_loop_task = tokio::spawn(async move {
            let mut packets = PacketBuffer::default();
            'read_loop: loop {
                let mut guard = match fd_watcher.readable().await {
                    Ok(guard) => guard,
                    Err(error) => {
                        tracing::error!(message_id = "r5N6izcO", ?error, "failed to wait for tun to become readable");
                        break;
                    }
                };
                let mut read_error = None;
                let mut eof = false;
                while let Some(buffer) = packets.buffer() {
                    match unistd::read(&fd_watcher, &mut buffer[..usize::from(u16::MAX)]) {
                        Ok(0) => {
                            eof = true;
                            break;
                        }
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
                let full_batch = packets.buffer().is_none();
                if !packets.is_empty() {
                    let sender = read_tunnel.lock().unwrap().clone();
                    sender.send(packets.take_iter());
                }
                // A read of 0 bytes does not clear the readiness: without the stop, this loop reads 0 bytes again at once.
                if eof {
                    tracing::info!(message_id = "eagh6Eof", "tun fd reached EOF, stopping reader");
                    break 'read_loop;
                }
                if let Some(error) = read_error {
                    tracing::error!(message_id = "eagh6Noh", ?error, "failed to read from tun");
                    break 'read_loop;
                }
                if full_batch {
                    tokio::task::yield_now().await;
                }
            }
        });
        let (writer, packets) = sync_channel(TUN_WRITE_QUEUE_LEN);
        let write_fd = fd.clone();
        let writer_queued = queued_packets.clone();
        let writer = thread::Builder::new()
            .name("ombra-tun-tx".into())
            .spawn(move || write_loop(&write_fd, mtu, packets, writer_queued))
            .inspect_err(|error| tracing::error!(message_id = "Lq3vTn8w", ?error, "failed to spawn the TUN writer: {error}"))
            .ok()
            .map(|_| writer);
        Ok(Self { fd, mtu, tunnel, read_loop_task: Some(read_loop_task.into()), writer, queued_packets })
    }

    /// False when the reader stopped: the fd failed or reached its end.
    pub fn is_alive(&self) -> bool {
        self.read_loop_task.as_ref().is_some_and(|task| !task.0.is_finished())
    }

    /// Gives the packets of the TUN to `tunnel` from now on. The fd and the threads stay, so the order of the packets
    /// does not change.
    pub fn set_tunnel(&self, tunnel: QuicWgConnPacketSender) {
        *self.tunnel.lock().unwrap() = tunnel;
    }

    /// Queues `packet` for the writer thread, or writes it here when there is no writer thread.
    pub fn write(&self, packet: Bytes) {
        let Some(writer) = &self.writer else {
            return write_packet(&self.fd, self.mtu, &packet);
        };
        self.enqueue(writer, TunWriteItem::One(packet));
    }

    /// Queues the packets of one receive batch as one item, and leaves `packets` empty.
    pub fn write_batch(&self, packets: &mut Vec<Bytes>) {
        let Some(writer) = &self.writer else {
            for packet in packets.drain(..) {
                write_packet(&self.fd, self.mtu, &packet);
            }
            return;
        };
        let capacity = packets.capacity();
        let item = match packets.len() {
            0 => return,
            1 => TunWriteItem::One(packets.swap_remove(0)),
            _ => TunWriteItem::Batch(std::mem::replace(packets, Vec::with_capacity(capacity))),
        };
        self.enqueue(writer, item);
    }

    fn enqueue(&self, writer: &SyncSender<TunWriteItem>, mut item: TunWriteItem) {
        let count = item.packet_count();
        let queued = self.queued_packets.fetch_add(count, Ordering::Relaxed);
        // As with one packet at a time (P25): the packets that fit go, only the others are dropped.
        let room = TUN_WRITE_QUEUE_LEN.saturating_sub(queued);
        if room < count {
            self.queued_packets.fetch_sub(count - room, Ordering::Relaxed);
            rate_limited_log!(
                TUN_MIN_LOG_SILENCE,
                tracing::warn!(message_id = "Kd8sQv3T", dropped = count - room, "TUN write queue full, packets dropped")
            );
            match &mut item {
                TunWriteItem::Batch(batch) if room > 0 => batch.truncate(room),
                _ => return,
            }
        }
        let count = item.packet_count();
        if let Err(error) = writer.try_send(item) {
            self.queued_packets.fetch_sub(count, Ordering::Relaxed);
            match error {
                TrySendError::Full(_) => rate_limited_log!(
                    TUN_MIN_LOG_SILENCE,
                    tracing::warn!(message_id = "Pq7xUr2k", "TUN write queue full, packet dropped")
                ),
                TrySendError::Disconnected(_) => rate_limited_log!(
                    TUN_MIN_LOG_SILENCE,
                    tracing::error!(message_id = "Hv4mWs9e", "TUN writer stopped, packet dropped")
                ),
            }
        }
    }
}

/// Writes the queued packets until the `Tun` is dropped (Ombra patch P10). A TUN write runs the receive path of the inner
/// IP stack in the calling thread. In its own thread it runs at the same time as the receive and the decryption of the
/// next packets: on the phone this doubled the download speed of Speed mode.
fn write_loop(fd: &OwnedFd, mtu: u16, packets: Receiver<TunWriteItem>, queued_packets: Arc<AtomicUsize>) {
    super::util::use_normal_scheduling();
    // The room of each packet is free as soon as it is written, also in a batch.
    let write = |packet: &Bytes| {
        write_packet(fd, mtu, packet);
        queued_packets.fetch_sub(1, Ordering::Relaxed);
    };
    for item in packets {
        match item {
            TunWriteItem::One(packet) => write(&packet),
            TunWriteItem::Batch(batch) => batch.iter().for_each(write),
        }
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
