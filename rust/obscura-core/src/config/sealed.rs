//! `config.json` encrypted at rest (Ombra patch P13). The OS side gives a 256-bit key at start; on Android Java keeps
//! it encrypted with an Android Keystore key. A copy of the file alone then shows no account number and no auth token.
//!
//! Format: [`HEADER`], 12-byte nonce, AES-256-GCM ciphertext with the 16-byte tag. The header is the AAD.

use ring::aead::{AES_256_GCM, Aad, LessSafeKey, NONCE_LEN, Nonce, UnboundKey};
use ring::rand::{SecureRandom, SystemRandom};

/// Magic and format version. A JSON config never starts with these bytes.
const HEADER: &[u8] = b"OMBRA-CONFIG\x01";

pub struct ConfigKey(LessSafeKey);

impl ConfigKey {
    /// `None` when `key` does not have 32 bytes.
    pub fn new(key: &[u8]) -> Option<Self> {
        UnboundKey::new(&AES_256_GCM, key).ok().map(|key| Self(LessSafeKey::new(key)))
    }

    pub(super) fn seal(&self, plaintext: &[u8]) -> Option<Vec<u8>> {
        // A random nonce for each save: 2^32 saves with one key are still safe.
        let mut nonce = [0; NONCE_LEN];
        SystemRandom::new().fill(&mut nonce).ok()?;
        let mut sealed = Vec::with_capacity(HEADER.len() + NONCE_LEN + plaintext.len() + AES_256_GCM.tag_len());
        sealed.extend_from_slice(HEADER);
        sealed.extend_from_slice(&nonce);
        let start = sealed.len();
        sealed.extend_from_slice(plaintext);
        let tag = self
            .0
            .seal_in_place_separate_tag(Nonce::assume_unique_for_key(nonce), Aad::from(HEADER), &mut sealed[start..])
            .ok()?;
        sealed.extend_from_slice(tag.as_ref());
        Some(sealed)
    }

    /// `None` for a wrong key or a damaged file.
    pub(super) fn open(&self, sealed: &[u8]) -> Option<Vec<u8>> {
        let rest = sealed.strip_prefix(HEADER)?;
        let (nonce, ciphertext) = rest.split_at_checked(NONCE_LEN)?;
        let nonce = Nonce::try_assume_unique_for_key(nonce).ok()?;
        let mut plaintext = ciphertext.to_vec();
        let len = self.0.open_in_place(nonce, Aad::from(HEADER), &mut plaintext).ok()?.len();
        plaintext.truncate(len);
        Some(plaintext)
    }
}

pub(super) fn is_sealed(bytes: &[u8]) -> bool {
    bytes.starts_with(HEADER)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_sealed_config_opens_only_with_its_key() {
        let key = ConfigKey::new(&[1; 32]).unwrap();
        let sealed = key.seal(br#"{"account_id":"55550123456780554337"}"#).unwrap();
        assert!(is_sealed(&sealed));
        assert!(!sealed.windows(4).any(|w| w == b"5555"), "no plaintext in the file");
        assert_eq!(key.open(&sealed).unwrap(), br#"{"account_id":"55550123456780554337"}"#);
        assert_ne!(key.seal(b"{}").unwrap(), key.seal(b"{}").unwrap(), "a new nonce for each save");

        assert!(ConfigKey::new(&[2; 32]).unwrap().open(&sealed).is_none(), "another key");
        let mut damaged = sealed.clone();
        *damaged.last_mut().unwrap() ^= 1;
        assert!(key.open(&damaged).is_none(), "a damaged file");
        assert!(key.open(&sealed[..HEADER.len() + 4]).is_none(), "a short file");
        assert!(!is_sealed(b"{}"));
        assert!(ConfigKey::new(&[1; 16]).is_none());
    }
}
