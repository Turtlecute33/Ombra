//! Small WireGuard messages share a QUIC packet (Ombra patch P4, E1; without padding P7; a lone message P11).
//!
//! With `pad_to_mtu`, quinn pads every packet to the MTU, so each small message, like an inner TCP ACK during a
//! download, costs a full-size packet on the uplink. quinn packs all datagrams that are queued when it builds a packet
//! into that packet, so small messages wait here for at most 2 ms, or until the next one would not fit into the same
//! packet, and then go to quinn together. Every packet keeps its full size.
//!
//! Without padding, each inner TCP ACK of a download was its own QUIC packet: on a phone about 1600 packets per second
//! for 60 Mbit/s, with about 1.5 % of them lost and each loss a congestion event. With the batches, the uplink had half
//! the packets and almost no losses, and one download stream ran at 100 to 150 Mbit/s instead of 60 (Ombra device test,
//! 2026-10-22).
//!
//! Only the messages that follow another one within 2 ms wait. A message after a quiet moment, like a TCP SYN, a DNS
//! query or a voice frame, goes at once. With padding, the wait of such a message only added delay: it went alone in a
//! padded packet after the 2 ms anyway. Without the wait, the idle RTT with padding fell from about 23.5 to 20 ms, and a
//! download needed the same uplink (Ombra device test, 2026-10-23).

use crate::tokio::AbortOnDrop;
use bytes::Bytes;
use smallvec::SmallVec;
use std::sync::{Arc, Mutex};
use tokio::sync::Notify;
use tokio::time::{Duration, Instant, sleep_until};

/// Messages of this size or larger go out at once: two of them do not fit into one packet.
const SMALL_MESSAGE_SIZE: usize = 600;

/// About one packet at the 1350-byte MTU, which has 1312 bytes of datagram room with an 8-byte connection ID, with
/// some room for longer connection IDs.
const BATCH_SIZE: usize = 1250;

const MAX_DELAY: Duration = Duration::from_millis(2);

/// The DATAGRAM frame type and its 2-byte length, which each message adds to the packet.
const DATAGRAM_FRAME_OVERHEAD: usize = 3;

type Messages = SmallVec<[Bytes; 64]>;

/// Sends WireGuard messages on a QUIC connection, the small ones coalesced.
pub struct DatagramCoalescer {
    shared: Arc<Shared>,
    _flush_task: AbortOnDrop,
}

struct Shared {
    conn: quinn::Connection,
    batch: Mutex<Batch>,
    batch_started: Notify,
}

impl DatagramCoalescer {
    pub fn spawn(conn: quinn::Connection) -> Self {
        let batch = Mutex::new(Batch::default());
        let shared = Arc::new(Shared { conn, batch, batch_started: Notify::new() });
        let flush_task = AbortOnDrop::spawn(flush_when_due(shared.clone()));
        Self { shared, _flush_task: flush_task }
    }

    /// Sends the messages in order, or holds them until more join them.
    pub fn send(&self, messages: impl IntoIterator<Item = Bytes>) {
        let room = self.shared.conn.max_datagram_size().unwrap_or(0);
        let now = Instant::now();
        let mut ready = Messages::new();
        let mut batch = self.shared.batch.lock().unwrap();
        let due_before = batch.due;
        for message in messages {
            batch.push(message, room, now, &mut ready);
        }
        // Sent while the batch is locked, so that the flush task cannot send older messages after these.
        send_datagrams(&self.shared.conn, ready);
        if batch.due.is_some() && batch.due != due_before {
            self.shared.batch_started.notify_one();
        }
    }
}

async fn flush_when_due(shared: Arc<Shared>) {
    loop {
        let due = shared.batch.lock().unwrap().due;
        match due {
            Some(due) => {
                sleep_until(due).await;
                let mut ready = Messages::new();
                let mut batch = shared.batch.lock().unwrap();
                batch.take_due(Instant::now(), &mut ready);
                send_datagrams(&shared.conn, ready);
            }
            // `notify_one` keeps the wakeup when the batch started before this waits.
            None => shared.batch_started.notified().await,
        }
    }
}

fn send_datagrams(conn: &quinn::Connection, messages: impl IntoIterator<Item = Bytes>) {
    for message in messages {
        if let Err(error) = conn.send_datagram(message) {
            rate_limited_log!(
                Duration::from_secs(1),
                tracing::error!(message_id = "8EkAaj9z", ?error, "error while sending quic datagram packet")
            );
        }
    }
}

/// The small messages that wait for more to share their packet.
#[derive(Default)]
struct Batch {
    messages: Messages,
    size: usize,
    due: Option<Instant>,
    /// When a message last went out.
    last_out: Option<Instant>,
}

impl Batch {
    /// Adds `message` and moves the messages that go out now to `ready`, in order. `room` is quinn's largest datagram:
    /// what one packet carries.
    fn push(&mut self, message: Bytes, room: usize, now: Instant, ready: &mut Messages) {
        if message.len() >= SMALL_MESSAGE_SIZE {
            self.take_all(now, ready);
            ready.push(message);
            return;
        }
        let quiet = self.last_out.is_none_or(|last_out| now.saturating_duration_since(last_out) >= MAX_DELAY);
        if self.messages.is_empty() && quiet {
            ready.push(message);
            self.last_out = Some(now);
            return;
        }
        let size = message.len() + DATAGRAM_FRAME_OVERHEAD;
        if self.size + size > room.min(BATCH_SIZE) {
            self.take_all(now, ready);
        }
        if self.messages.is_empty() {
            self.due = Some(now + MAX_DELAY);
        }
        self.messages.push(message);
        self.size += size;
    }

    fn take_due(&mut self, now: Instant, ready: &mut Messages) {
        if self.due.is_some_and(|due| due <= now) {
            self.take_all(now, ready);
        }
    }

    fn take_all(&mut self, now: Instant, ready: &mut Messages) {
        ready.extend(self.messages.drain(..));
        self.size = 0;
        self.due = None;
        self.last_out = Some(now);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// quinn's largest datagram at the 1350-byte MTU with an 8-byte connection ID.
    const ROOM_AT_1350: usize = 1312;

    fn message(id: u8, len: usize) -> Bytes {
        Bytes::from(vec![id; len])
    }

    fn ids(messages: &Messages) -> Vec<u8> {
        messages.iter().map(|message| message[0]).collect()
    }

    /// A batch whose last message went out at `last_out`.
    fn busy_since(last_out: Instant) -> Batch {
        Batch { last_out: Some(last_out), ..Batch::default() }
    }

    #[test]
    fn small_messages_that_follow_closely_wait_2_ms() {
        let start = Instant::now();
        let mut batch = busy_since(start);
        let mut ready = Messages::new();
        for id in 0..3 {
            batch.push(
                message(id, 100),
                ROOM_AT_1350,
                start + Duration::from_micros(300 * u64::from(id)),
                &mut ready,
            );
        }
        assert!(ready.is_empty());
        assert_eq!(batch.due, Some(start + MAX_DELAY), "the first message sets the deadline");

        batch.take_due(start + Duration::from_micros(1999), &mut ready);
        assert!(ready.is_empty());
        batch.take_due(start + MAX_DELAY, &mut ready);
        assert_eq!(ids(&ready), [0, 1, 2]);
        assert_eq!(batch.due, None);
    }

    #[test]
    fn a_message_that_does_not_fit_sends_the_batch_and_starts_the_next() {
        let start = Instant::now();
        let later = start + Duration::from_millis(1);
        let mut batch = busy_since(start);
        let mut ready = Messages::new();
        // 12 messages of 100 bytes take 1236 bytes with their frame headers; a 13th does not fit into 1250.
        for id in 0..12 {
            batch.push(message(id, 100), ROOM_AT_1350, start, &mut ready);
        }
        assert!(ready.is_empty());
        batch.push(message(12, 100), ROOM_AT_1350, later, &mut ready);
        assert_eq!(ids(&ready), (0..12).collect::<Vec<_>>());
        assert_eq!(ids(&batch.messages), [12]);
        assert_eq!(batch.due, Some(later + MAX_DELAY));
    }

    #[test]
    fn a_large_message_goes_at_once_after_the_waiting_ones() {
        let start = Instant::now();
        let mut batch = busy_since(start);
        let mut ready = Messages::new();
        batch.push(message(0, 100), ROOM_AT_1350, start, &mut ready);
        batch.push(message(1, 599), ROOM_AT_1350, start, &mut ready);
        assert!(ready.is_empty());
        batch.push(message(2, SMALL_MESSAGE_SIZE), ROOM_AT_1350, start, &mut ready);
        assert_eq!(ids(&ready), [0, 1, 2]);
        assert!(batch.messages.is_empty());
        assert_eq!(batch.due, None);
    }

    #[test]
    fn a_message_after_a_quiet_moment_goes_at_once() {
        let us = Duration::from_micros;
        let start = Instant::now();
        let mut batch = Batch::default();
        let mut ready = Messages::new();
        batch.push(message(0, 100), ROOM_AT_1350, start, &mut ready);
        assert_eq!(ids(&ready), [0], "the first message after a quiet moment goes at once");
        assert_eq!(batch.due, None);

        ready.clear();
        batch.push(message(1, 100), ROOM_AT_1350, start + us(300), &mut ready);
        batch.push(message(2, 100), ROOM_AT_1350, start + us(600), &mut ready);
        assert!(ready.is_empty());
        let due = start + us(300) + MAX_DELAY;
        assert_eq!(batch.due, Some(due));
        batch.take_due(due, &mut ready);
        assert_eq!(ids(&ready), [1, 2]);

        ready.clear();
        batch.push(message(3, 100), ROOM_AT_1350, due + us(100), &mut ready);
        assert!(ready.is_empty(), "still busy right after the batch went out");
        batch.take_due(due + us(100) + MAX_DELAY, &mut ready);
        assert_eq!(ids(&ready), [3]);

        ready.clear();
        batch.push(message(4, 100), ROOM_AT_1350, due + Duration::from_millis(10), &mut ready);
        assert_eq!(ids(&ready), [4], "quiet again");
    }

    #[test]
    fn a_smaller_mtu_makes_smaller_batches() {
        let start = Instant::now();
        let mut batch = busy_since(start);
        let mut ready = Messages::new();
        // At the 1200-byte MTU quinn's largest datagram is 1162 bytes: 11 messages of 100 bytes fit, a 12th does not.
        for id in 0..12 {
            batch.push(message(id, 100), 1162, start, &mut ready);
        }
        assert_eq!(ids(&ready), (0..11).collect::<Vec<_>>());
        assert_eq!(ids(&batch.messages), [11]);
    }
}
