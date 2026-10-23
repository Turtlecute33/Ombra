use crate::constants::DNS_CACHE_SEED;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::net::SocketAddr;
use std::time::{Duration, SystemTime};

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct DnsCache {
    entries: HashMap<String, Vec<SocketAddr>>,
    /// When each entry came from a system lookup (Ombra patch P3). Seed entries and entries from older config files
    /// have none, so they count as expired. A time in the future (the clock went back) counts as expired too.
    #[serde(default)]
    resolved_at: HashMap<String, SystemTime>,
}

/// What the cache has for a name.
#[derive(Debug, PartialEq, Eq)]
pub enum CachedAddrs {
    /// From a system lookup that is younger than the TTL.
    Fresh(Vec<SocketAddr>),
    /// From an older lookup, or seed addresses.
    Expired(Vec<SocketAddr>),
    Missing,
}

impl DnsCache {
    pub fn get(&self, name: &str) -> Vec<SocketAddr> {
        self.entries.get(name).cloned().unwrap_or_default()
    }

    pub fn lookup(&self, name: &str, ttl: Duration) -> CachedAddrs {
        let addrs = self.get(name);
        let fresh = self.resolved_at.get(name).and_then(|at| at.elapsed().ok()).is_some_and(|age| age < ttl);
        match (addrs.is_empty(), fresh) {
            (true, _) => CachedAddrs::Missing,
            (false, true) => CachedAddrs::Fresh(addrs),
            (false, false) => CachedAddrs::Expired(addrs),
        }
    }

    /// Stores the result of a system lookup.
    pub fn set(&mut self, name: &str, addr: &[SocketAddr]) {
        self.entries.insert(name.to_string(), addr.to_vec());
        self.resolved_at.insert(name.to_string(), SystemTime::now());
    }

    /// Keeps the addresses but makes them expired, so the next resolve of each name also starts a system lookup.
    pub fn expire_all(&mut self) {
        self.resolved_at.clear();
    }
}

impl Default for DnsCache {
    fn default() -> Self {
        Self {
            entries: HashMap::from_iter(DNS_CACHE_SEED.iter().map(|(name, addrs)| (name.to_string(), addrs.to_vec()))),
            resolved_at: HashMap::new(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::constants::DEFAULT_API_DOMAIN;
    use serde_json::json;

    const TTL: Duration = Duration::from_secs(3600);

    #[test]
    fn seed_addresses_are_expired() {
        let cache = DnsCache::default();
        let CachedAddrs::Expired(addrs) = cache.lookup(DEFAULT_API_DOMAIN, TTL) else {
            panic!("the seed must be refreshed by a lookup");
        };
        assert!(!addrs.is_empty());
        assert_eq!(cache.lookup("unknown.example", TTL), CachedAddrs::Missing);
    }

    #[test]
    fn lookup_result_is_fresh_until_the_ttl_or_expire_all() {
        let addrs: Vec<SocketAddr> = vec!["192.0.2.1:0".parse().unwrap()];
        let mut cache = DnsCache::default();
        cache.set("api.example", &addrs);
        assert_eq!(cache.lookup("api.example", TTL), CachedAddrs::Fresh(addrs.clone()));
        assert_eq!(cache.lookup("api.example", Duration::ZERO), CachedAddrs::Expired(addrs.clone()));

        // The time survives a config save and load.
        let mut reloaded: DnsCache = serde_json::from_value(serde_json::to_value(&cache).unwrap()).unwrap();
        assert_eq!(reloaded.lookup("api.example", TTL), CachedAddrs::Fresh(addrs.clone()));

        reloaded.expire_all();
        assert_eq!(reloaded.lookup("api.example", TTL), CachedAddrs::Expired(addrs));
    }

    #[test]
    fn cache_from_an_older_config_is_expired() {
        let cache: DnsCache = serde_json::from_value(json!({"entries": {"api.example": ["192.0.2.1:0"]}})).unwrap();
        assert_eq!(
            cache.lookup("api.example", TTL),
            CachedAddrs::Expired(vec!["192.0.2.1:0".parse().unwrap()])
        );
    }
}
