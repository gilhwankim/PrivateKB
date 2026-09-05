//! Local execution logs only. Never recurse into storage or the database.
use chrono::{DateTime, Utc};
use std::fs::{self, OpenOptions};
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Condvar, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const FILE_LIMIT: u64 = 10_000_000;
const TOTAL_LIMIT: u64 = 100_000_000;
const RETENTION: Duration = Duration::from_secs(7 * 86400);
const CLEANUP_INTERVAL: Duration = Duration::from_secs(60);
// Writers and cleanup share a lock: an archive cannot disappear during rotation.
static LOG_LOCK: Mutex<()> = Mutex::new(());

fn regular(path: &Path) -> bool {
    fs::symlink_metadata(path).is_ok_and(|m| m.is_file() && !m.file_type().is_symlink())
}

fn ensure_directory(dir: &Path) -> io::Result<()> {
    fs::create_dir_all(dir)?;
    let metadata = fs::symlink_metadata(dir)?;
    if !metadata.is_dir() || metadata.file_type().is_symlink() {
        return Err(io::Error::other("Log directory must be a real directory"));
    }
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        if metadata.file_attributes() & 0x400 != 0 {
            return Err(io::Error::other(
                "Log directory must not be a reparse point",
            ));
        }
    }
    Ok(())
}

fn day(time: SystemTime) -> u64 {
    time.duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
        / 86400
}

fn archive(path: &Path, now: SystemTime) -> io::Result<()> {
    if !regular(path) {
        return Err(io::Error::other("Only regular log files can be rotated"));
    }
    let stem = path.file_stem().and_then(|v| v.to_str()).unwrap_or("log");
    let stamp = now
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    for sequence in 0..1000 {
        let target = path.with_file_name(format!("{stem}-{stamp}-{sequence}.log"));
        if !target.exists() {
            return fs::rename(path, target);
        }
    }
    Err(io::Error::other("Cannot allocate log archive name"))
}

fn rotate_if_needed(path: &Path, now: SystemTime, limit: u64) -> io::Result<bool> {
    match fs::symlink_metadata(path) {
        Ok(meta) if !meta.is_file() || meta.file_type().is_symlink() => {
            Err(io::Error::other("Log path is not a regular file"))
        }
        Ok(meta)
            if meta.len() > 0 && (meta.len() >= limit || day(meta.modified()?) != day(now)) =>
        {
            archive(path, now)?;
            Ok(true)
        }
        Ok(_) => Ok(false),
        Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(false),
        Err(e) => Err(e),
    }
}

fn managed_name(name: &str) -> bool {
    ["runtime.log", "backend.log", "postgresql.log"].contains(&name)
        || ["runtime-", "backend-", "postgresql-"]
            .iter()
            .any(|prefix| {
                name.strip_prefix(prefix)
                    .and_then(|n| n.strip_suffix(".log"))
                    .is_some_and(|suffix| {
                        !suffix.is_empty()
                            && suffix
                                .bytes()
                                .all(|b| b.is_ascii_digit() || b == b'-' || b == b'_')
                    })
            })
}

// Native PostgreSQL collector owns its files. Fail closed if its active-file
// metadata cannot be read, and protect newer files against collector rotations.
fn postgres_guard(dir: &Path) -> (bool, Option<SystemTime>, Vec<PathBuf>) {
    let database = dir.parent().unwrap_or(dir).join("database");
    let running = database.join("postmaster.pid").exists();
    let Ok(content) = fs::read_to_string(database.join("current_logfiles")) else {
        return (running, None, vec![]);
    };
    let mut paths = vec![];
    let mut newest = None;
    for line in content.lines() {
        let Some((_, value)) = line.split_once(' ') else {
            continue;
        };
        let path = PathBuf::from(value.trim());
        let path = if path.is_absolute() {
            path
        } else {
            database.join(path)
        };
        if let Ok(path) = path.canonicalize() {
            if let Ok(modified) = fs::metadata(&path).and_then(|m| m.modified()) {
                newest = Some(newest.map_or(modified, |n: SystemTime| n.max(modified)));
            }
            paths.push(path);
        }
    }
    (running && paths.is_empty(), newest, paths)
}

fn cleanup_locked(
    dir: &Path,
    now: SystemTime,
    total_limit: u64,
    retention: Duration,
) -> io::Result<()> {
    let (protect_postgres, pg_modified, pg_active) = postgres_guard(dir);
    let mut files = vec![];
    let mut total = 0u64;
    for entry in fs::read_dir(dir)? {
        let entry = entry?;
        let path = entry.path();
        let name = entry.file_name().to_string_lossy().into_owned();
        if !managed_name(&name) || !regular(&path) {
            continue;
        }
        let meta = fs::metadata(&path)?;
        let modified = meta.modified()?;
        let active = ["runtime.log", "backend.log", "postgresql.log"].contains(&name.as_str())
            || (name.starts_with("postgresql-")
                && (protect_postgres
                    || pg_active
                        .iter()
                        .any(|p| path.canonicalize().is_ok_and(|v| &v == p))
                    || pg_modified.is_some_and(|t| modified >= t)));
        total = total.saturating_add(meta.len());
        files.push((path, modified, meta.len(), active));
    }
    files.sort_by_key(|(_, modified, _, _)| *modified);
    let mut failure = None;
    for (path, modified, size, active) in files {
        if !active
            && (now.duration_since(modified).unwrap_or_default() >= retention
                || total > total_limit)
        {
            match fs::remove_file(path) {
                Ok(()) => total = total.saturating_sub(size),
                Err(e) => failure = Some(e), // Try other archives even if one is locked.
            }
        }
    }
    failure.map_or(Ok(()), Err)
}

fn append_at(path: &Path, bytes: &[u8], now: SystemTime, limit: u64) -> io::Result<()> {
    let dir = path
        .parent()
        .ok_or_else(|| io::Error::other("Missing log directory"))?;
    ensure_directory(dir)?;
    let _guard = LOG_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let mut remaining = bytes;
    while !remaining.is_empty() {
        let rotated = rotate_if_needed(path, now, limit)?;
        if rotated {
            // Reserve one new active file's growth; cleanup failure must not stop logging.
            let _ = cleanup_locked(dir, now, TOTAL_LIMIT - FILE_LIMIT, RETENTION);
        }
        let size = fs::metadata(path).map(|m| m.len()).unwrap_or(0);
        let count = remaining.len().min(limit.saturating_sub(size) as usize);
        if count == 0 {
            return Err(io::Error::other("Log rotation did not free space"));
        }
        let mut file = OpenOptions::new().create(true).append(true).open(path)?;
        file.write_all(&remaining[..count])?;
        remaining = &remaining[count..];
    }
    Ok(())
}

pub(crate) fn runtime(path: &Path, level: &str, message: &str) {
    let now = SystemTime::now();
    let stamp: DateTime<Utc> = now.into();
    let message = message.replace(['\r', '\n'], " ");
    let line = format!(
        "{} [{level}] {message}\n",
        stamp.format("%Y-%m-%dT%H:%M:%S%.3fZ")
    );
    if append_at(path, line.as_bytes(), now, FILE_LIMIT).is_err() {
        eprintln!("PrivateKB: local runtime log write failed");
    }
}

// Bounded byte buffers, not read_line(): even an enormous stack trace cannot
// allocate an unbounded string. Continue draining on disk errors so Java won't hang.
pub(crate) fn capture(
    mut reader: impl Read + Send + 'static,
    path: PathBuf,
) -> io::Result<JoinHandle<()>> {
    thread::Builder::new()
        .name("privatekb-log-capture".into())
        .spawn(move || {
            let mut buffer = [0u8; 8192];
            let mut warned = false;
            loop {
                match reader.read(&mut buffer) {
                    Ok(0) => break,
                    Ok(count) => {
                        if append_at(&path, &buffer[..count], SystemTime::now(), FILE_LIMIT)
                            .is_err()
                            && !warned
                        {
                            eprintln!(
                                "PrivateKB: backend log write failed; continuing to drain output"
                            );
                            warned = true;
                        }
                    }
                    Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
                    Err(_) => break,
                }
            }
        })
}

// Only call for PostgreSQL's old bootstrap log before pg_ctl starts the server.
pub(crate) fn prepare_postgres_bootstrap(path: &Path) -> io::Result<()> {
    let _guard = LOG_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    if regular(path) && fs::metadata(path)?.len() > 0 {
        archive(path, SystemTime::now())?;
    }
    cleanup_locked(
        path.parent().unwrap(),
        SystemTime::now(),
        TOTAL_LIMIT,
        RETENTION,
    )
}

pub(crate) struct Maintenance {
    stop: Arc<(Mutex<bool>, Condvar)>,
    worker: Option<JoinHandle<()>>,
}

impl Maintenance {
    pub(crate) fn start(dir: PathBuf) -> io::Result<Self> {
        ensure_directory(&dir)?;
        if maintain(&dir).is_err() {
            eprintln!("PrivateKB: initial log cleanup failed; retrying next minute");
        }
        let stop = Arc::new((Mutex::new(false), Condvar::new()));
        let signal = stop.clone();
        let worker = thread::Builder::new()
            .name("privatekb-log-cleanup".into())
            .spawn(move || {
                let (lock, wake) = &*signal;
                loop {
                    let guard = lock.lock().unwrap_or_else(|e| e.into_inner());
                    let (guard, _) = wake
                        .wait_timeout_while(guard, CLEANUP_INTERVAL, |stop| !*stop)
                        .unwrap_or_else(|e| e.into_inner());
                    if *guard {
                        break;
                    }
                    drop(guard);
                    if maintain(&dir).is_err() {
                        eprintln!("PrivateKB: log cleanup failed; retrying next minute");
                    }
                }
            })?;
        Ok(Self {
            stop,
            worker: Some(worker),
        })
    }
}

fn maintain(dir: &Path) -> io::Result<()> {
    let _guard = LOG_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let now = SystemTime::now();
    for name in ["runtime.log", "backend.log"] {
        rotate_if_needed(&dir.join(name), now, FILE_LIMIT)?;
    }
    cleanup_locked(dir, now, TOTAL_LIMIT, RETENTION)
}

impl Drop for Maintenance {
    fn drop(&mut self) {
        let (lock, wake) = &*self.stop;
        *lock.lock().unwrap_or_else(|e| e.into_inner()) = true;
        wake.notify_all();
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn directory() -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "pkb-logs-{}",
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir_all(&dir).unwrap();
        dir
    }
    #[test]
    fn bounds_each_file_and_preserves_all_bytes() {
        let dir = directory();
        let path = dir.join("backend.log");
        append_at(&path, &[b'x'; 257], SystemTime::now(), 100).unwrap();
        let sizes: Vec<_> = fs::read_dir(&dir)
            .unwrap()
            .map(|e| e.unwrap().metadata().unwrap().len())
            .collect();
        assert_eq!(sizes.iter().sum::<u64>(), 257);
        assert!(sizes.iter().all(|s| *s <= 100));
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn rotates_on_utc_date_change() {
        let dir = directory();
        let path = dir.join("runtime.log");
        append_at(&path, b"old", SystemTime::now(), 100).unwrap();
        append_at(
            &path,
            b"new",
            SystemTime::now() + Duration::from_secs(86400),
            100,
        )
        .unwrap();
        assert_eq!(fs::read(path).unwrap(), b"new");
        assert_eq!(fs::read_dir(&dir).unwrap().count(), 2);
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn cleanup_preserves_active_and_unrelated_files_and_does_not_recurse() {
        let dir = directory();
        for name in [
            "runtime.log",
            "backend.log",
            "postgresql.log",
            "notes.log",
            "backend-1-0.log",
        ] {
            fs::write(dir.join(name), b"log").unwrap();
        }
        fs::create_dir(dir.join("storage")).unwrap();
        fs::write(dir.join("storage/backend-2-0.log"), b"document").unwrap();
        cleanup_locked(&dir, SystemTime::now() + RETENTION, 1, RETENTION).unwrap();
        assert!(!dir.join("backend-1-0.log").exists());
        for name in [
            "runtime.log",
            "backend.log",
            "postgresql.log",
            "notes.log",
            "storage/backend-2-0.log",
        ] {
            assert!(dir.join(name).exists());
        }
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn protects_postgres_current_file_and_fails_closed_without_metadata() {
        let root = directory();
        let dir = root.join("logs");
        let db = root.join("database");
        fs::create_dir_all(&dir).unwrap();
        fs::create_dir_all(&db).unwrap();
        fs::write(db.join("postmaster.pid"), "1").unwrap();
        let active = dir.join("postgresql-20260905_120000.log");
        fs::write(&active, "active").unwrap();
        cleanup_locked(&dir, SystemTime::now() + RETENTION, 1, RETENTION).unwrap();
        assert!(active.exists());
        fs::write(
            db.join("current_logfiles"),
            format!("stderr {}\n", active.display()),
        )
        .unwrap();
        let old = dir.join("postgresql-20260904_120000.log");
        fs::write(&old, "old").unwrap();
        let file = OpenOptions::new().write(true).open(&old).unwrap();
        file.set_times(
            fs::FileTimes::new().set_modified(SystemTime::now() - Duration::from_secs(60)),
        )
        .unwrap();
        drop(file);
        cleanup_locked(&dir, SystemTime::now() + RETENTION, 1, RETENTION).unwrap();
        assert!(active.exists());
        assert!(!old.exists());
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn maintenance_stops_without_waiting_for_interval_and_runtime_has_timestamp() {
        let dir = directory();
        let worker = Maintenance::start(dir.clone()).unwrap();
        runtime(&dir.join("runtime.log"), "WARN", "test\nmessage");
        let text = fs::read_to_string(dir.join("runtime.log")).unwrap();
        assert!(text.contains("Z [WARN] test message"));
        let start = std::time::Instant::now();
        drop(worker);
        assert!(start.elapsed() < Duration::from_secs(2));
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn total_budget_removes_oldest_archive_even_before_expiry() {
        let dir = directory();
        let old = dir.join("backend-1-0.log");
        fs::write(&old, [0; 30]).unwrap();
        let file = OpenOptions::new().write(true).open(&old).unwrap();
        file.set_times(
            fs::FileTimes::new().set_modified(SystemTime::now() - Duration::from_secs(30)),
        )
        .unwrap();
        drop(file);
        fs::write(dir.join("runtime-2-0.log"), [0; 30]).unwrap();
        fs::write(dir.join("runtime.log"), [0; 30]).unwrap();
        cleanup_locked(&dir, SystemTime::now(), 60, RETENTION).unwrap();
        assert!(!old.exists());
        assert!(dir.join("runtime-2-0.log").exists());
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn expires_archives_even_when_well_below_budget() {
        let dir = directory();
        fs::write(dir.join("backend-1-0.log"), "old").unwrap();
        cleanup_locked(
            &dir,
            SystemTime::now() + RETENTION + Duration::from_secs(1),
            TOTAL_LIMIT,
            RETENTION,
        )
        .unwrap();
        assert!(!dir.join("backend-1-0.log").exists());
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn captures_bounded_output_and_keeps_draining_if_log_path_is_invalid() {
        let dir = directory();
        let path = dir.join("backend.log");
        capture(std::io::Cursor::new(vec![b'x'; 20000]), path.clone())
            .unwrap()
            .join()
            .unwrap();
        assert_eq!(fs::metadata(&path).unwrap().len(), 20000);
        fs::remove_file(&path).unwrap();
        fs::create_dir(&path).unwrap();
        capture(std::io::Cursor::new(vec![b'x'; 20000]), path)
            .unwrap()
            .join()
            .unwrap();
        fs::remove_dir_all(dir).unwrap();
    }
}
