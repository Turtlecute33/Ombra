use crate::client_state::WeakClientStateHandle;
use crate::config::dns_cache::CachedAddrs;
use obscuravpn_api::reexports::reqwest::Url;
use obscuravpn_api::reexports::reqwest::dns::{Addrs, Name, Resolve, Resolving};
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;
use tokio::time::timeout;

/// How long API addresses from a system lookup are used without a new lookup (Ombra patch P3). Lookups go to the
/// resolver of the physical network, outside the tunnel; upstream made one on every resolve, also on a cache hit.
const CACHE_TTL: Duration = Duration::from_secs(60 * 60);

pub struct DnsResolver {
    client_state: WeakClientStateHandle,
}

impl DnsResolver {
    pub fn new(client_state: WeakClientStateHandle) -> Arc<Self> {
        Arc::new(Self { client_state })
    }
}

impl Resolve for DnsResolver {
    fn resolve(&self, name: Name) -> Resolving {
        let cached = match self.client_state.upgrade() {
            None => {
                tracing::warn!(message_id = "F0ZR7dTm", "can't read from DNS cache of dropped client state");
                CachedAddrs::Missing
            }
            Some(client_state) => {
                // The API client resolves an alternative host only after the primary host failed, maybe because of
                // its cached address. That request can then succeed, so the failure would not expire the cache.
                if !is_base_url_host(&client_state.base_url(), name.as_str()) {
                    client_state.expire_dns_cache();
                }
                client_state.borrow().config().dns_cache.lookup(name.as_str(), CACHE_TTL)
            }
        };
        let addrs = match cached {
            CachedAddrs::Fresh(addrs) => addrs,
            // Answer from the cache at once, so a blocked resolver cannot block the API, and refresh it in the background.
            CachedAddrs::Expired(addrs) => {
                tokio::spawn(resolve_and_cache(self.client_state.clone(), name.as_str().to_string()));
                addrs
            }
            CachedAddrs::Missing => {
                tracing::warn!(message_id = "ooPh8ahc", name = name.as_str(), "DNS cache miss, wait for resolver");
                let resolving = tokio::spawn(resolve_and_cache(self.client_state.clone(), name.as_str().to_string()));
                return Box::pin(async move {
                    let addrs = resolving.await.expect("DNS resolution task panicked")?;
                    let addrs: Addrs = Box::new(addrs.into_iter());
                    Ok(addrs)
                });
            }
        };
        let addrs: Addrs = Box::new(addrs.into_iter());
        Box::pin(std::future::ready(Ok(addrs)))
    }
}

fn is_base_url_host(base_url: &str, name: &str) -> bool {
    Url::parse(base_url)
        .ok()
        .and_then(|url| url.host_str().map(|host| host == name))
        .unwrap_or(true)
}

async fn resolve_and_cache(client_state: WeakClientStateHandle, name: String) -> Result<Vec<SocketAddr>, Box<dyn std::error::Error + Send + Sync>> {
    const TIMEOUT: Duration = Duration::from_secs(60);

    let name = name.as_str();
    match timeout(TIMEOUT, tokio::net::lookup_host((name, 0u16))).await {
        Ok(Ok(addrs)) => {
            let addrs: Vec<_> = addrs.collect();
            if !addrs.is_empty() {
                tracing::info!(message_id = "ea1Ooquu", name, ?addrs, "DNS resolution succeeded");
                match client_state.upgrade() {
                    None => tracing::warn!(message_id = "2aZU1KWD", "can't write to DNS cache of dropped client state"),
                    Some(client_state) => client_state.update_dns_cache(name, &addrs),
                }
            } else {
                tracing::warn!(message_id = "Uu3ohPh4", name, "DNS resolution returned no addresses");
            }
            Ok(addrs)
        }
        Ok(Err(error)) => {
            tracing::warn!(message_id = "ieC5ahv3", name, ?error, "DNS resolution failed: {error}");
            Err(error.into())
        }
        Err(error) => {
            tracing::warn!(message_id = "RwX9EdwE", name, ?error, "DNS resolution timed out: {error}");
            Err(error.into())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::client_state::{ClientState, ClientStateHandle};
    use crate::wg_key_store::WgKeyStore;
    use tokio::time::sleep;

    // The hosts file resolves this name, so the tests make no network lookup.
    const NAME: &str = "localhost";

    fn client_state(config_dir: &std::path::Path) -> ClientStateHandle {
        let client_state = ClientState::new(config_dir.into(), WgKeyStore::None, None, "test".into(), false).unwrap();
        // NAME stands in for the API host.
        client_state.change_config(|config| config.api_url = Some(format!("https://{NAME}/api")));
        client_state
    }

    fn cached_addr() -> SocketAddr {
        "192.0.2.1:0".parse().unwrap()
    }

    async fn resolve(client_state: &ClientStateHandle) -> Vec<SocketAddr> {
        let resolver = DnsResolver::new(client_state.downgrade());
        resolver.resolve(NAME.parse().unwrap()).await.unwrap().collect()
    }

    fn cache(client_state: &ClientStateHandle) -> CachedAddrs {
        client_state.borrow().config().dns_cache.lookup(NAME, CACHE_TTL)
    }

    fn is_lookup_result(cached: CachedAddrs) -> bool {
        matches!(cached, CachedAddrs::Fresh(addrs) if addrs.iter().all(|addr| addr.ip().is_loopback()))
    }

    #[tokio::test]
    async fn fresh_hit_makes_no_lookup() {
        let dir = tempfile::tempdir().unwrap();
        let client_state = client_state(dir.path());
        client_state.update_dns_cache(NAME, &[cached_addr()]);

        assert_eq!(resolve(&client_state).await, [cached_addr()]);
        // A lookup of the name would replace the address within milliseconds.
        sleep(Duration::from_millis(200)).await;
        assert_eq!(cache(&client_state), CachedAddrs::Fresh(vec![cached_addr()]));
    }

    #[tokio::test]
    async fn expired_hit_answers_from_the_cache_and_looks_up_in_the_background() {
        let dir = tempfile::tempdir().unwrap();
        let client_state = client_state(dir.path());
        client_state.update_dns_cache(NAME, &[cached_addr()]);
        client_state.expire_dns_cache();

        assert_eq!(resolve(&client_state).await, [cached_addr()]);
        timeout(Duration::from_secs(5), async {
            while !is_lookup_result(cache(&client_state)) {
                sleep(Duration::from_millis(20)).await;
            }
        })
        .await
        .expect("the background lookup did not refresh the cache");
    }

    #[tokio::test]
    async fn a_lookup_of_an_alternative_api_host_expires_the_cache() {
        // The API client resolves an alternative host only after the primary host failed.
        let dir = tempfile::tempdir().unwrap();
        let client_state = client_state(dir.path());
        client_state.update_dns_cache(NAME, &[cached_addr()]);
        client_state.change_config(|config| config.api_url = Some("https://api.example/api".into()));

        assert_eq!(resolve(&client_state).await, [cached_addr()]);
        timeout(Duration::from_secs(5), async {
            while !is_lookup_result(cache(&client_state)) {
                sleep(Duration::from_millis(20)).await;
            }
        })
        .await
        .expect("the cache did not expire");
    }

    #[tokio::test]
    async fn miss_waits_for_the_lookup() {
        let dir = tempfile::tempdir().unwrap();
        let client_state = client_state(dir.path());
        assert_eq!(cache(&client_state), CachedAddrs::Missing);

        let addrs = resolve(&client_state).await;
        assert!(!addrs.is_empty() && addrs.iter().all(|addr| addr.ip().is_loopback()), "{addrs:?}");
        assert!(is_lookup_result(cache(&client_state)));
    }
}
