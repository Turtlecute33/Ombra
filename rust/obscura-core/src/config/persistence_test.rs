use std::fs;
#[cfg(unix)]
use std::os::unix::fs::PermissionsExt;
use std::path::Path;
use std::sync::Arc;
use std::time::SystemTime;

use obscuravpn_api::cmd::ExitList;
use obscuravpn_api::types::AccountId;
use obscuravpn_api::types::CityCode;
use obscuravpn_api::types::CountryCode;
use obscuravpn_api::types::OneExit;
use obscuravpn_api::types::OneRelay;
use obscuravpn_api::types::RelayPreferredExit;
use tempfile::tempdir;
use uuid::Uuid;

use crate::config::CONFIG_FILE;
use crate::config::Config;
use crate::config::ConfigKey;
use crate::config::ConfigLoadError;
use crate::config::PinnedLocation;
use crate::config::cached::ConfigCached;
use crate::config::load;
use crate::config::load_with_key;
use crate::config::save;
use crate::config::save_with_key;
use crate::exit_selection::ExitSelector;
use crate::wg_key_store::WgKeyStore;

fn random_config() -> Config {
    Config {
        api_url: Some(Uuid::new_v4().to_string()),
        account_id: Some(AccountId::from_string_unchecked(Uuid::new_v4().to_string())),
        old_account_ids: vec![AccountId::from_string_unchecked(Uuid::new_v4().to_string())],
        ..Default::default()
    }
}

#[test]
fn load_no_config() {
    let dir = Path::new("/var/empty/");
    assert_eq!(load(dir, &WgKeyStore::Plaintext).unwrap(), Default::default());
}

#[test]
fn load_config() {
    let config = random_config();

    let dir = tempdir().unwrap();

    save(dir.as_ref(), &config).unwrap();

    assert_eq!(load(dir.as_ref(), &WgKeyStore::Plaintext).unwrap(), config);
}

#[test]
fn load_invalid_json() {
    let dir = tempdir().unwrap();
    let file = dir.as_ref().join(CONFIG_FILE);

    let corrupted = "{";
    fs::write(&file, corrupted).unwrap();

    // Load returns a default config.
    assert_eq!(load(dir.as_ref(), &WgKeyStore::Plaintext).unwrap(), Default::default());

    let backup_files = fs::read_dir(&dir)
        .unwrap()
        .map(|entry| entry.unwrap())
        .filter(|entry| entry.file_name().to_string_lossy().starts_with("config-backup-"))
        .collect::<Vec<_>>();

    assert_eq!(backup_files.len(), 1);
    let backup = &backup_files[0];

    // Original file was saved.
    let backup_contents = fs::read_to_string(backup.path()).unwrap();
    assert_eq!(backup_contents, corrupted);

    // Config file was eagerly updated to the default.
    let config_contents = fs::read_to_string(&file).unwrap();
    assert_ne!(config_contents, corrupted);
}

#[test]
fn load_error_does_not_quote_the_file() {
    let json_error = serde_json::from_str::<Config>(r#""12345678901234567890""#).unwrap_err();
    assert!(
        json_error.to_string().contains("12345678901234567890"),
        "serde quotes the value: {json_error}"
    );
    let message = ConfigLoadError::DeserializeError(json_error).to_string();
    assert!(!message.contains("12345678901234567890"), "{message}");
}

fn key(byte: u8) -> ConfigKey {
    ConfigKey::new(&[byte; 32]).unwrap()
}

fn backups(dir: &Path) -> Vec<Vec<u8>> {
    fs::read_dir(dir)
        .unwrap()
        .map(|entry| entry.unwrap())
        .filter(|entry| entry.file_name().to_string_lossy().starts_with("config-backup-"))
        .map(|entry| fs::read(entry.path()).unwrap())
        .collect()
}

fn contains(haystack: &[u8], needle: &str) -> bool {
    haystack.windows(needle.len()).any(|window| window == needle.as_bytes())
}

/// Ombra patch P13.
#[test]
fn an_encrypted_config_shows_no_values_and_loads_with_its_key() {
    let config = random_config();
    let marker = config.api_url.clone().unwrap();
    let dir = tempdir().unwrap();

    save_with_key(dir.as_ref(), &config, Some(&key(7))).unwrap();
    assert!(!contains(&fs::read(dir.as_ref().join(CONFIG_FILE)).unwrap(), &marker));
    assert_eq!(load_with_key(dir.as_ref(), &WgKeyStore::Plaintext, Some(&key(7))).unwrap(), config);
}

/// Ombra patch P13: the file of an older version is encrypted when it loads, and its plain text backups go.
#[test]
fn a_plain_text_config_is_encrypted_when_it_loads() {
    let config = random_config();
    let marker = config.api_url.clone().unwrap();
    let dir = tempdir().unwrap();
    let file = dir.as_ref().join(CONFIG_FILE);

    save(dir.as_ref(), &config).unwrap();
    fs::write(dir.as_ref().join("config-backup-2026-01-01T00-00-00.000.json"), &marker).unwrap();
    assert!(contains(&fs::read(&file).unwrap(), &marker));
    assert_eq!(load_with_key(dir.as_ref(), &WgKeyStore::Plaintext, Some(&key(7))).unwrap(), config);
    assert!(!contains(&fs::read(&file).unwrap(), &marker));
    assert_eq!(load_with_key(dir.as_ref(), &WgKeyStore::Plaintext, Some(&key(7))).unwrap(), config);
    assert!(backups(dir.as_ref()).is_empty());
}

/// Ombra patch P13: Java gives a new key only when the old one is gone, so the file can never open again. It resets
/// like a file that does not parse, and its copy stays encrypted.
#[test]
fn an_encrypted_config_without_its_key_resets() {
    let config = random_config();
    for wrong_key in [Some(key(8)), None] {
        let dir = tempdir().unwrap();
        save_with_key(dir.as_ref(), &config, Some(&key(7))).unwrap();
        let sealed = fs::read(dir.as_ref().join(CONFIG_FILE)).unwrap();

        assert_eq!(
            load_with_key(dir.as_ref(), &WgKeyStore::Plaintext, wrong_key.as_ref()).unwrap(),
            Config::default()
        );
        assert_eq!(backups(dir.as_ref()), vec![sealed]);
        assert_eq!(
            load_with_key(dir.as_ref(), &WgKeyStore::Plaintext, wrong_key.as_ref()).unwrap(),
            Config::default()
        );
    }
}

#[test]
fn load_empty() {
    let dir = tempdir().unwrap();
    let file = dir.as_ref().join(CONFIG_FILE);

    let empty = r#"{"removed_field":"abc"}"#;
    fs::write(&file, empty).unwrap();

    // Load returns a default config.
    assert_eq!(load(dir.as_ref(), &WgKeyStore::Plaintext).unwrap(), Default::default());

    let backup_files = fs::read_dir(&dir)
        .unwrap()
        .map(|entry| entry.unwrap())
        .filter(|entry| entry.file_name().to_string_lossy().starts_with("config-backup-"))
        .collect::<Vec<_>>();

    assert_eq!(backup_files.len(), 0);
}

#[cfg(unix)]
#[test]
fn load_no_permission() {
    let config = random_config();

    let dir = tempdir().unwrap();

    save(dir.as_ref(), &config).unwrap();

    let file = dir.as_ref().join(CONFIG_FILE);

    // Mess up the file permissions so that reads fail.
    let original_permissions = fs::metadata(&file).unwrap().permissions();
    let mut permissions = original_permissions.clone();
    permissions.set_readonly(true);
    permissions.set_mode(0o0);
    fs::set_permissions(&file, permissions).unwrap();

    assert!(load(dir.as_ref(), &WgKeyStore::Plaintext).is_err());
}

#[test]
fn test_ignore_invalid_fields() {
    let example_config = Config {
        api_host_alternate: Some("relay.example".into()),
        api_url: Some("myapi".into()),
        account_id: Some(AccountId::from_string_unchecked("myaccount".into())),
        old_account_ids: vec![AccountId::from_string_unchecked("oldaccount".into())],
        local_tunnels_ids: (),
        exit: (),
        in_new_account_flow: true,
        cached_auth_token: Some("myauth".into()),
        cached_exits: Some(ConfigCached::new(
            Arc::new(ExitList {
                exits: vec![OneExit {
                    id: "ABC-123".into(),
                    city_code: CityCode { country_code: CountryCode("ca".into()), city_code: "yyz".into() },
                    city_name: "Toronto".into(),
                    datacenter_id: 34,
                    provider_id: "foo123".into(),
                    provider_url: "https://servers.example/foo123".into(),
                    provider_name: "Cheap Server Rentals".into(),
                    provider_homepage_url: "https://example.com".into(),
                    tier: 0,
                }],
            }),
            super::cached::Version::artificial(),
        )),
        cached_relays: Some(ConfigCached::new(
            Arc::new(vec![OneRelay {
                id: "NYC-001".into(),
                city_code: CityCode { country_code: CountryCode("us".into()), city_code: "nyc".into() },
                city_name: "New York".into(),
                preferred_exits: vec![RelayPreferredExit { id: "nyc-wg-30".into() }],
                ip_v4: "8.8.31.3".parse().unwrap(),
                ports: vec![53, 443],
                tls_cert: vec![1, 2, 3],
            }]),
            super::cached::Version::artificial(),
        )),
        pinned_locations: vec![PinnedLocation { country_code: "CA".into(), city_code: "yyz".into(), pinned_at: SystemTime::UNIX_EPOCH }],
        last_chosen_exit: Some("mylastexit".into()),
        last_chosen_exit_selector: ExitSelector::City { city_code: CityCode { country_code: CountryCode("ca".into()), city_code: "yyz".into() } },
        last_exit_selector: ExitSelector::City { city_code: CityCode { country_code: CountryCode("ca".into()), city_code: "yyz".into() } },
        tunnel_active: false,
        tunnel_args: Default::default(),
        sni_relay: Some("relay.obscura.net".into()),
        wireguard_key_cache: Default::default(),
        dns: Default::default(),
        local_network_access: Default::default(),
        tailscale_bypass: Default::default(),
        wireguard_bypass: Default::default(),
        dns_cache: Default::default(),
        use_wireguard_key_cache: (),
        cached_account_status: Default::default(),
        auto_connect: true,
        feature_flags: Default::default(),
        force_tcp_tls_relay_transport: (),
        dns_content_block: Default::default(),
        owned_udp_tunnel_ids: vec![Uuid::new_v4()],
        wg_key_max_age_secs: Some(86_400),
    };
    let example_json = match serde_json::to_value(&example_config).unwrap() {
        serde_json::Value::Object(m) => m,
        other => panic!("Expected map, got {:?}", other),
    };

    let corrupt_values = [
        serde_json::Value::Object(Default::default()),
        serde_json::Value::Number(7.into()),
        serde_json::Value::Null,
        serde_json::Value::Array(vec![serde_json::Value::Bool(true)]),
    ];

    for (field, _) in &example_json {
        let mut mutated = example_json.clone();
        for corrupt in &corrupt_values {
            eprintln!("Setting {field:?} = {corrupt:?}");
            mutated[field] = corrupt.clone();

            let reserialized = serde_json::to_string_pretty(&mutated).unwrap();
            let parsed = serde_json::from_str::<Config>(&reserialized).unwrap();
            let json_repr = match serde_json::to_value(&parsed).unwrap() {
                serde_json::Value::Object(m) => m,
                other => panic!("Expected map, got {:?}", other),
            };
            for (k, v) in json_repr {
                if &k == field {
                    continue;
                }
                assert_eq!(v, example_json[&k], "Field {k:?} doesn't match.");
            }
        }

        eprintln!("Removing {field:?}");
        mutated.remove(field);
        let reserialized = serde_json::to_string_pretty(&mutated).unwrap();
        let _ = serde_json::from_str::<Config>(&reserialized).unwrap();
    }
}

/// Ombra on Android uses `WgKeyStore::Keychain` backed by the Android Keystore: the secret key goes only to the
/// store callback and never into config.json, and a restart with the stored secret gives back the same key pair.
#[test]
fn keychain_key_store_keeps_secret_out_of_config() {
    use base64::{Engine as _, engine::general_purpose::STANDARD};
    use std::sync::Mutex;

    let stored: Arc<Mutex<Vec<[u8; 32]>>> = Default::default();
    let key_store = |secret_key: Option<Vec<u8>>| {
        let stored = stored.clone();
        WgKeyStore::Keychain {
            secret_key: secret_key.map(zeroize::Zeroizing::new),
            set_secret_key: Box::new(move |secret_key: &[u8; 32]| {
                stored.lock().unwrap().push(*secret_key);
                true
            }),
        }
    };
    let dir = tempdir().unwrap();

    // First start: no key anywhere, so the first use makes one and stores it.
    let key_store_empty = key_store(None);
    let mut config = load(dir.as_ref(), &key_store_empty).unwrap();
    let (secret_key, public_key) = config.wireguard_key_cache.use_key_pair(&key_store_empty);
    let secret_key = secret_key.to_bytes();
    assert_eq!(*stored.lock().unwrap(), vec![secret_key]);
    save(dir.as_ref(), &config).unwrap();
    let json = fs::read_to_string(dir.as_ref().join(CONFIG_FILE)).unwrap();
    assert!(json.contains(&public_key.to_string()));
    assert!(!json.contains(&STANDARD.encode(secret_key)));

    // Restart with the stored secret: same key pair, nothing stored again.
    let key_store_restored = key_store(Some(secret_key.to_vec()));
    let mut config = load(dir.as_ref(), &key_store_restored).unwrap();
    let (restored_secret_key, restored_public_key) = config.wireguard_key_cache.use_key_pair(&key_store_restored);
    assert_eq!(restored_secret_key.to_bytes(), secret_key);
    assert_eq!(restored_public_key, public_key);
    assert_eq!(stored.lock().unwrap().len(), 1);

    // Restart without it (Keystore key lost): a new key pair is made and stored.
    let key_store_lost = key_store(None);
    let mut config = load(dir.as_ref(), &key_store_lost).unwrap();
    let (new_secret_key, new_public_key) = config.wireguard_key_cache.use_key_pair(&key_store_lost);
    assert_ne!(new_public_key, public_key);
    assert_eq!(stored.lock().unwrap().last(), Some(&new_secret_key.to_bytes()));
}

/// Ombra patch P5: the ids of the Speed mode tunnels survive a restart, so that their tunnels are deleted then. Config
/// files of older versions have none. (`test_ignore_invalid_fields` covers broken values.)
#[test]
fn owned_udp_tunnel_ids_survive_a_restart() {
    let dir = tempdir().unwrap();
    let ids = vec![Uuid::new_v4(), Uuid::new_v4()];
    let config = Config { owned_udp_tunnel_ids: ids.clone(), ..random_config() };
    save(dir.as_ref(), &config).unwrap();
    assert_eq!(load(dir.as_ref(), &WgKeyStore::Plaintext).unwrap().owned_udp_tunnel_ids, ids);

    let mut json = serde_json::to_value(&config).unwrap();
    json.as_object_mut().unwrap().remove("owned_udp_tunnel_ids");
    fs::write(dir.as_ref().join(CONFIG_FILE), json.to_string()).unwrap();
    let loaded = load(dir.as_ref(), &WgKeyStore::Plaintext).unwrap();
    assert!(loaded.owned_udp_tunnel_ids.is_empty());
    assert_eq!(loaded.account_id, config.account_id);
}
