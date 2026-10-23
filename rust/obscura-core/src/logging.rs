use camino::{Utf8Path, Utf8PathBuf};
use std::sync::{Arc, Mutex, Weak};
use tracing_appender::non_blocking::{NonBlocking, WorkerGuard};
use tracing_subscriber::{
    Layer, Registry,
    filter::{Directive, EnvFilter, LevelFilter},
    layer::SubscriberExt as _,
    registry,
};

#[derive(Debug)]
pub struct LogPersistence {
    path: Utf8PathBuf,
    guard: Weak<WorkerGuard>,
}

impl LogPersistence {
    pub fn log_dir(&self) -> &Utf8Path {
        &self.path
    }
}

impl Drop for LogPersistence {
    fn drop(&mut self) {
        LOG_GUARDS
            .lock()
            .unwrap()
            .retain(|other| !Weak::ptr_eq(&self.guard, &Arc::downgrade(other)));
    }
}

static LOG_GUARDS: Mutex<Vec<Arc<WorkerGuard>>> = Mutex::new(Vec::new());

pub fn flush_and_stop_persisted_log() {
    LOG_GUARDS.lock().unwrap().clear();
}

extern "C" fn at_exit() {
    flush_and_stop_persisted_log();
}

#[cfg(not(target_os = "macos"))]
fn build_log_roller(log_dir: &Utf8Path) -> anyhow::Result<(NonBlocking, LogPersistence)> {
    use logroller::{Compression, LogRollerBuilder, Rotation, RotationSize, TimeZone};

    static LOG_FILE_NAME: &str = "rust-log.ndjson";
    const MAX_LOG_FILES: u64 = 24;
    const MAX_LOG_SIZE: u64 = 10_000_000;

    if log_dir.as_str().is_empty() {
        anyhow::bail!("no log dir specified");
    }
    LogRollerBuilder::new(log_dir, LOG_FILE_NAME.as_ref())
        // The rotation often runs behind a bit, but at low log pressure
        // (i.e. not TRACE) it's good enough
        .rotation(Rotation::SizeBased(RotationSize::Bytes(MAX_LOG_SIZE)))
        .max_keep_files(MAX_LOG_FILES)
        .time_zone(TimeZone::UTC)
        // https://linux.die.net/man/1/xz
        .compression(Compression::XZ(2))
        .build()
        .map(NonBlocking::new)
        .map(|(writer, guard)| {
            let guard = Arc::new(guard);
            let persistence = LogPersistence { path: log_dir.to_owned(), guard: Arc::downgrade(&guard) };
            LOG_GUARDS.lock().unwrap().push(guard);
            (writer, persistence)
        })
        .map_err(Into::into)
}

#[cfg(target_os = "macos")]
fn build_log_roller(log_dir: &Utf8Path) -> anyhow::Result<(NonBlocking, LogPersistence)> {
    anyhow::bail!("specified log dir on a platform that doesn't support log persistence: {log_dir}")
}

/// Target of the 1 Hz data path statistics (Ombra patch P4, A2 and C5). They are DEBUG events, so the live log shows
/// them only when `init` gets `datapath_stats` (Android: Diagnostics on) or `RUST_LOG` asks for them. The log files
/// never get them: a per-second trace of traffic volume does not belong on the device.
pub const DATAPATH_STATS_TARGET: &str = "ombra::datapath_stats";

// `EnvFilter` doesn't impl `Clone`
fn filter() -> EnvFilter {
    EnvFilter::from_default_env().add_directive(LevelFilter::INFO.into())
}

fn live_filter(datapath_stats: bool) -> EnvFilter {
    match datapath_stats {
        true => filter().add_directive(datapath_stats_directive(LevelFilter::DEBUG)),
        false => filter(),
    }
}

/// A directive for a target replaces a `RUST_LOG` directive for the same target, so `RUST_LOG` cannot turn this off.
fn persisted_filter() -> EnvFilter {
    filter().add_directive(datapath_stats_directive(LevelFilter::OFF))
}

fn datapath_stats_directive(level: LevelFilter) -> Directive {
    format!("{DATAPATH_STATS_TARGET}={level}").parse().expect("valid directive")
}

pub fn init(base_layer: impl Layer<Registry> + Send + Sync, persistence_dir: Option<&Utf8Path>, datapath_stats: bool) -> Option<LogPersistence> {
    let registry = registry().with(base_layer.with_filter(live_filter(datapath_stats)));
    let persistence = match persistence_dir.map(build_log_roller) {
        Some(Ok((writer, persistence))) => {
            let fs_layer = tracing_subscriber::fmt::Layer::default()
                .json()
                .with_writer(writer)
                .with_filter(persisted_filter());
            tracing::subscriber::set_global_default(registry.with(fs_layer)).expect("failed to set global subscriber");
            unsafe { libc::atexit(at_exit) };
            Some(persistence)
        }
        result => {
            tracing::subscriber::set_global_default(registry).expect("failed to set global subscriber");
            if let Some(Err(error)) = result {
                tracing::error!(message_id = "RlVghVYB", ?error, "failed to initialize log persistence");
            }
            None
        }
    };
    tracing::info!(message_id = "rrnKY3lZ", "logging initialized");
    std::panic::set_hook(Box::new(|panic_info| {
        tracing::error!(message_id = "W6fhvnSf", "{panic_info}\n{:#}", std::backtrace::Backtrace::force_capture());
        flush_and_stop_persisted_log();
    }));
    tracing::info!(message_id = "o0PqqebH", "panic logging hook set");
    persistence
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use tracing::{Event, Subscriber};
    use tracing_subscriber::layer::Context;

    struct CountEvents(Arc<AtomicUsize>);

    impl<S: Subscriber> Layer<S> for CountEvents {
        fn on_event(&self, _event: &Event<'_>, _ctx: Context<'_, S>) {
            self.0.fetch_add(1, Ordering::Relaxed);
        }
    }

    /// The number of data path stats events that a layer with `filter` gets.
    fn datapath_stats_events(filter: EnvFilter) -> usize {
        let count = Arc::new(AtomicUsize::new(0));
        let subscriber = registry().with(CountEvents(count.clone()).with_filter(filter));
        tracing::subscriber::with_default(subscriber, || {
            tracing::debug!(target: DATAPATH_STATS_TARGET, "stats");
            tracing::info!(target: DATAPATH_STATS_TARGET, "stats");
        });
        count.load(Ordering::Relaxed)
    }

    #[test]
    fn datapath_stats_reach_the_live_log_only_when_asked_and_never_the_files() {
        assert_eq!(datapath_stats_events(live_filter(true)), 2);
        assert_eq!(datapath_stats_events(persisted_filter()), 0);
    }
}
