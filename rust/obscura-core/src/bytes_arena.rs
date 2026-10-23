//! Buffers for the data path without a heap allocation and a copy per packet (Ombra patch P4, C3).

use bytes::{Bytes, BytesMut};

/// Room for about 50 full-size WireGuard messages.
const CHUNK_SIZE: usize = 64 * 1024;

/// Hands out zeroed space that WireGuard writes a message or packet into, and turns the written part into `Bytes`
/// without a copy. The space comes from 64 KiB chunks, so there is one allocation per chunk instead of one per packet.
/// A chunk is freed when the arena moved on and the last `Bytes` taken from it is dropped.
#[derive(Default)]
pub struct BytesArena {
    chunk: BytesMut,
}

impl BytesArena {
    /// At least `len` bytes of zeroed space. Call `take` for the part that was written.
    pub fn space(&mut self, len: usize) -> &mut [u8] {
        if self.chunk.len() < len {
            self.chunk = BytesMut::zeroed(len.max(CHUNK_SIZE));
        }
        &mut self.chunk
    }

    /// The first `len` bytes of the last `space`.
    pub fn take(&mut self, len: usize) -> Bytes {
        self.chunk.split_to(len).freeze()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn taken_bytes_keep_their_content_while_the_arena_moves_on() {
        let mut arena = BytesArena::default();
        let mut taken = Vec::new();
        for i in 0..200u8 {
            let space = arena.space(1312);
            assert!(space.len() >= 1312);
            space[..1312].fill(i);
            taken.push(arena.take(1312));
        }
        for (i, bytes) in (0..200u8).zip(&taken) {
            assert_eq!(bytes.len(), 1312);
            assert!(bytes.iter().all(|byte| *byte == i));
        }
    }

    #[test]
    fn space_larger_than_a_chunk() {
        let mut arena = BytesArena::default();
        assert_eq!(arena.space(CHUNK_SIZE + 1).len(), CHUNK_SIZE + 1);
        assert_eq!(arena.take(CHUNK_SIZE + 1).len(), CHUNK_SIZE + 1);
        assert_eq!(arena.space(10).len(), CHUNK_SIZE);
    }
}
