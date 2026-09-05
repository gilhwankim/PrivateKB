use getrandom::getrandom;
use crate::log_management;
use serde::{Deserialize, Serialize};
use std::fs::{self, OpenOptions};
use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddrV4, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Output, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Manager};

#[cfg(windows)]
use std::os::windows::process::CommandExt;
#[cfg(windows)]
use std::{ffi::c_void, mem::size_of, os::windows::io::AsRawHandle, ptr};
#[cfg(windows)]
use windows_sys::Win32::Foundation::{CloseHandle, HANDLE};
#[cfg(windows)]
use windows_sys::Win32::System::JobObjects::{
    AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation,
    SetInformationJobObject, TerminateJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
    JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
};

#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;
const MANIFEST_SCHEMA_VERSION: u32 = 2;
const BACKEND_READY_TIMEOUT: Duration = Duration::from_secs(90);
const PROCESS_STOP_TIMEOUT: Duration = Duration::from_secs(10);
const POSTGRES_FALLBACK_PORT_START: u16 = 15433;
const POSTGRES_FALLBACK_PORT_END: u16 = 15463;
const BACKEND_FALLBACK_PORT_START: u16 = 18080;
const BACKEND_FALLBACK_PORT_END: u16 = 18110;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RuntimeManifest {
    schema_version: u32,
    runtime_version: String,
    java: JavaManifest,
    backend: BackendManifest,
    database: DatabaseManifest,
    ocr: OcrManifest,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct JavaManifest {
    executable: String,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct BackendManifest {
    jar: String,
    port: u16,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct DatabaseManifest {
    root: String,
    port: u16,
    name: String,
    user: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct OcrManifest {
    executable_directory: String,
    data_path: String,
}

#[derive(Debug)]
struct ResolvedRuntime {
    runtime_version: String,
    java_executable: PathBuf,
    backend_jar: PathBuf,
    backend_port: u16,
    postgres_root: PathBuf,
    postgres_port: u16,
    database_name: String,
    database_user: String,
    ocr_executable_directory: PathBuf,
    ocr_data_path: PathBuf,
}

#[cfg(windows)]
struct BackendJob {
    handle: isize,
}

#[cfg(windows)]
impl BackendJob {
    fn attach(child: &Child) -> Result<Self, String> {
        let handle = unsafe { CreateJobObjectW(ptr::null(), ptr::null()) };
        if handle.is_null() {
            return Err(format!(
                "백엔드 보호 작업을 만들 수 없습니다: {}",
                std::io::Error::last_os_error()
            ));
        }

        let mut information = JOBOBJECT_EXTENDED_LIMIT_INFORMATION::default();
        information.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        let configured = unsafe {
            SetInformationJobObject(
                handle,
                JobObjectExtendedLimitInformation,
                &information as *const JOBOBJECT_EXTENDED_LIMIT_INFORMATION as *const c_void,
                size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
            )
        };
        if configured == 0 {
            let error = std::io::Error::last_os_error();
            unsafe {
                CloseHandle(handle);
            }
            return Err(format!("백엔드 보호 작업을 구성할 수 없습니다: {error}"));
        }

        let assigned = unsafe { AssignProcessToJobObject(handle, child.as_raw_handle() as HANDLE) };
        if assigned == 0 {
            let error = std::io::Error::last_os_error();
            unsafe {
                CloseHandle(handle);
            }
            return Err(format!(
                "백엔드 프로세스를 보호 작업에 연결할 수 없습니다: {error}"
            ));
        }

        Ok(Self {
            handle: handle as isize,
        })
    }

    fn terminate(&self) {
        unsafe {
            TerminateJobObject(self.handle as HANDLE, 1);
        }
    }
}

#[cfg(windows)]
impl Drop for BackendJob {
    fn drop(&mut self) {
        unsafe {
            CloseHandle(self.handle as HANDLE);
        }
    }
}

#[cfg(not(windows))]
struct BackendJob;

#[cfg(not(windows))]
impl BackendJob {
    fn attach(_child: &Child) -> Result<Self, String> {
        Ok(Self)
    }

    fn terminate(&self) {}
}

struct ManagedProcesses {
    backend: Option<Child>,
    backend_job: Option<BackendJob>,
    backend_port: u16,
    shutdown_token: String,
    bridge_token: String,
    pg_ctl: PathBuf,
    postgres_data: PathBuf,
    postgres_started: bool,
}

impl ManagedProcesses {
    fn empty(runtime: &ResolvedRuntime, postgres_data: PathBuf) -> Self {
        Self {
            backend: None,
            backend_job: None,
            backend_port: runtime.backend_port,
            shutdown_token: String::new(),
            bridge_token: String::new(),
            pg_ctl: runtime.postgres_root.join("bin").join("pg_ctl.exe"),
            postgres_data,
            postgres_started: false,
        }
    }

    fn shutdown(&mut self, log_path: &Path) {
        if let Some(child) = self.backend.as_mut() {
            if child.try_wait().ok().flatten().is_none() {
                let _ = request_backend_shutdown(self.backend_port, &self.shutdown_token);
                if !wait_for_child_exit(child, PROCESS_STOP_TIMEOUT) {
                    if let Some(job) = self.backend_job.as_ref() {
                        job.terminate();
                    }
                    if !wait_for_child_exit(child, Duration::from_secs(2)) {
                        let _ = child.kill();
                        let _ = wait_for_child_exit(child, Duration::from_secs(2));
                    }
                    log_management::runtime(
                        log_path,
                        "WARN",
                        "백엔드가 제한 시간 안에 종료되지 않아 강제 종료했습니다.",
                    );
                }
            }
        }
        self.backend = None;
        self.backend_job = None;

        if self.postgres_started {
            let mut command = hidden_command(&self.pg_ctl);
            let result = command
                .arg("stop")
                .arg("-D")
                .arg(&self.postgres_data)
                .arg("-m")
                .arg("fast")
                .arg("-w")
                .arg("-t")
                .arg("30")
                .output();
            match result {
                Ok(output) if output.status.success() => {
                    write_runtime_log(
                        log_path,
                        "애플리케이션 전용 PostgreSQL을 안전하게 종료했습니다.",
                    );
                }
                Ok(output) => log_management::runtime(
                    log_path,
                    "ERROR",
                    &format!(
                        "PostgreSQL 종료 명령이 실패했습니다(종료 코드 {}).",
                        output.status.code().unwrap_or(-1)
                    ),
                ),
                Err(error) => log_management::runtime(
                    log_path,
                    "ERROR",
                    &format!("PostgreSQL 종료 명령을 실행하지 못했습니다: {error}"),
                ),
            }
            self.postgres_started = false;
        }
    }
}

pub struct RuntimeSupervisor {
    processes: Arc<Mutex<Option<ManagedProcesses>>>,
    startup_thread: Mutex<Option<JoinHandle<()>>>,
    stop_requested: Arc<AtomicBool>,
    shutdown_started: AtomicBool,
    log_path: Arc<Mutex<Option<PathBuf>>>,
    log_maintenance: Mutex<Option<log_management::Maintenance>>,
    status: Arc<Mutex<RuntimeStatusView>>,
}

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub(crate) enum RuntimePhase {
    External,
    Starting,
    Ready,
    Error,
    Stopped,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct RuntimeStatusView {
    phase: RuntimePhase,
    error_code: Option<String>,
    backend_port: Option<u16>,
}

#[derive(Clone)]
pub(crate) struct DesktopBridgeConnection {
    pub(crate) backend_port: u16,
    pub(crate) token: String,
}

impl RuntimeSupervisor {
    pub fn new() -> Self {
        Self {
            processes: Arc::new(Mutex::new(None)),
            startup_thread: Mutex::new(None),
            stop_requested: Arc::new(AtomicBool::new(false)),
            shutdown_started: AtomicBool::new(false),
            log_path: Arc::new(Mutex::new(None)),
            log_maintenance: Mutex::new(None),
            status: Arc::new(Mutex::new(RuntimeStatusView {
                phase: RuntimePhase::Starting,
                error_code: None,
                backend_port: None,
            })),
        }
    }

    pub(crate) fn status(&self) -> RuntimeStatusView {
        lock_or_recover(&self.status).clone()
    }

    fn update_status(&self, phase: RuntimePhase, error_code: Option<&str>) {
        *lock_or_recover(&self.status) = RuntimeStatusView {
            phase,
            error_code: error_code.map(str::to_owned),
            backend_port: None,
        };
    }

    pub fn start(&self, app: &AppHandle) {
        let resource_dir = match app.path().resource_dir() {
            Ok(path) => windows_process_path(path),
            Err(error) => {
                self.update_status(RuntimePhase::Error, Some("RESOURCE_PATH_UNAVAILABLE"));
                eprintln!("PrivateKB 리소스 경로를 확인하지 못했습니다: {error}");
                return;
            }
        };
        let manifest_path = resource_dir.join("runtime").join("manifest.json");
        if !manifest_path.is_file() {
            self.update_status(RuntimePhase::External, None);
            eprintln!("PrivateKB 개발 모드: 외부 백엔드와 PostgreSQL을 사용합니다.");
            return;
        }

        let app_data_dir = match app.path().app_local_data_dir() {
            Ok(path) => windows_process_path(path),
            Err(error) => {
                self.update_status(RuntimePhase::Error, Some("APP_DATA_PATH_UNAVAILABLE"));
                eprintln!("PrivateKB 로컬 데이터 경로를 확인하지 못했습니다: {error}");
                return;
            }
        };
        let log_path = app_data_dir.join("logs").join("runtime.log");
        *lock_or_recover(&self.log_path) = Some(log_path.clone());
        match log_management::Maintenance::start(app_data_dir.join("logs")) {
            Ok(maintenance) => *lock_or_recover(&self.log_maintenance) = Some(maintenance),
            Err(error) => {
                self.update_status(RuntimePhase::Error, Some("LOG_DIRECTORY_UNAVAILABLE"));
                log_management::runtime(&log_path, "ERROR", &format!("로그 관리 초기화 실패: {error}"));
                return;
            }
        }

        let processes = Arc::clone(&self.processes);
        let stop_requested = Arc::clone(&self.stop_requested);
        let runtime_status = Arc::clone(&self.status);
        let startup_log_path = log_path.clone();
        let handle = thread::Builder::new()
            .name("privatekb-runtime-startup".to_owned())
            .spawn(move || {
                write_runtime_log(&startup_log_path, "관리형 로컬 서비스 시작을 요청했습니다.");
                match start_managed_runtime(
                    &manifest_path,
                    &app_data_dir,
                    &startup_log_path,
                    &stop_requested,
                ) {
                    Ok(mut started) => {
                        if stop_requested.load(Ordering::SeqCst) {
                            started.shutdown(&startup_log_path);
                            *lock_or_recover(&runtime_status) = RuntimeStatusView {
                                phase: RuntimePhase::Stopped,
                                error_code: None,
                                backend_port: None,
                            };
                        } else {
                            let backend_port = started.backend_port;
                            *lock_or_recover(&processes) = Some(started);
                            *lock_or_recover(&runtime_status) = RuntimeStatusView {
                                phase: RuntimePhase::Ready,
                                error_code: None,
                                backend_port: Some(backend_port),
                            };
                        }
                    }
                    Err(error) => {
                        *lock_or_recover(&runtime_status) = RuntimeStatusView {
                            phase: RuntimePhase::Error,
                            error_code: Some("RUNTIME_START_FAILED".to_owned()),
                            backend_port: None,
                        };
                        log_management::runtime(
                            &startup_log_path,
                            "ERROR",
                            &format!("관리형 로컬 서비스 시작 실패: {error}"),
                        );
                    }
                }
            });

        match handle {
            Ok(handle) => *lock_or_recover(&self.startup_thread) = Some(handle),
            Err(error) => {
                self.update_status(RuntimePhase::Error, Some("START_THREAD_FAILED"));
                log_management::runtime(
                    &log_path,
                    "ERROR",
                    &format!("런타임 시작 작업을 만들지 못했습니다: {error}"),
                );
            }
        }
    }

    pub fn shutdown(&self) {
        if self.shutdown_started.swap(true, Ordering::SeqCst) {
            return;
        }
        self.stop_requested.store(true, Ordering::SeqCst);
        self.update_status(RuntimePhase::Stopped, None);

        if let Some(handle) = lock_or_recover(&self.startup_thread).take() {
            let _ = handle.join();
        }

        let log_path = lock_or_recover(&self.log_path).clone();
        if let Some(processes) = lock_or_recover(&self.processes).as_mut() {
            if let Some(path) = log_path.as_deref() {
                processes.shutdown(path);
            }
        }
        lock_or_recover(&self.log_maintenance).take();
    }

    pub(crate) fn bridge_connection(&self) -> Result<DesktopBridgeConnection, String> {
        let processes = lock_or_recover(&self.processes);
        let managed = processes
            .as_ref()
            .ok_or_else(|| "관리형 데스크톱 서비스가 아직 준비되지 않았습니다.".to_owned())?;
        if managed.bridge_token.is_empty() {
            return Err("데스크톱 문서 연결 세션이 준비되지 않았습니다.".to_owned());
        }
        Ok(DesktopBridgeConnection {
            backend_port: managed.backend_port,
            token: managed.bridge_token.clone(),
        })
    }
}

fn start_managed_runtime(
    manifest_path: &Path,
    app_data_dir: &Path,
    log_path: &Path,
    stop_requested: &AtomicBool,
) -> Result<ManagedProcesses, String> {
    let mut runtime = load_runtime(manifest_path)?;
    fs::create_dir_all(app_data_dir.join("logs"))
        .map_err(|error| format!("로그 디렉터리를 만들 수 없습니다: {error}"))?;
    fs::create_dir_all(app_data_dir.join("storage"))
        .map_err(|error| format!("문서 저장 디렉터리를 만들 수 없습니다: {error}"))?;
    let config_dir = app_data_dir.join("config");
    fs::create_dir_all(&config_dir)
        .map_err(|error| format!("설정 디렉터리를 만들 수 없습니다: {error}"))?;

    write_runtime_log(
        log_path,
        &format!("런타임 {} 구성을 확인했습니다.", runtime.runtime_version),
    );

    let postgres_data = app_data_dir.join("database");
    let mut managed = ManagedProcesses::empty(&runtime, postgres_data.clone());
    let password_path = config_dir.join("database-password");
    let password = load_or_create_secret(&password_path)?;

    let result = (|| {
        initialize_postgres(&runtime, &postgres_data, &password_path, log_path)?;
        if stop_requested.load(Ordering::SeqCst) {
            return Err("애플리케이션 종료로 시작 작업을 취소했습니다.".to_owned());
        }

        managed.postgres_started = start_postgres(&mut runtime, &postgres_data, log_path)?;
        ensure_database(&runtime, &password)?;

        if stop_requested.load(Ordering::SeqCst) {
            return Err("애플리케이션 종료로 시작 작업을 취소했습니다.".to_owned());
        }

        let selected_backend_port = select_available_port(
            runtime.backend_port,
            BACKEND_FALLBACK_PORT_START..=BACKEND_FALLBACK_PORT_END,
        )?;
        if selected_backend_port != runtime.backend_port {
            write_runtime_log(
                log_path,
                &format!(
                    "기본 앱 연결 포트 {}을 다른 프로그램이 사용 중이어서 {}으로 자동 조정했습니다.",
                    runtime.backend_port, selected_backend_port
                ),
            );
        }
        runtime.backend_port = selected_backend_port;
        managed.backend_port = selected_backend_port;

        let shutdown_token = generate_secret()?;
        let bridge_token = generate_secret()?;
        managed.shutdown_token = shutdown_token.clone();
        managed.bridge_token = bridge_token.clone();
        let mut backend = start_backend(
            &runtime,
            app_data_dir,
            &password,
            &shutdown_token,
            &bridge_token,
        )?;
        let backend_job = match BackendJob::attach(&backend) {
            Ok(job) => job,
            Err(error) => {
                let _ = backend.kill();
                let _ = wait_for_child_exit(&mut backend, Duration::from_secs(2));
                return Err(error);
            }
        };
        managed.backend = Some(backend);
        managed.backend_job = Some(backend_job);

        wait_for_backend(
            managed
                .backend
                .as_mut()
                .expect("백엔드 프로세스가 있어야 합니다."),
            runtime.backend_port,
            stop_requested,
        )?;
        write_runtime_log(log_path, "Spring Boot 백엔드 준비가 완료되었습니다.");
        Ok(())
    })();

    if let Err(error) = result {
        managed.shutdown(log_path);
        return Err(error);
    }
    Ok(managed)
}

fn load_runtime(manifest_path: &Path) -> Result<ResolvedRuntime, String> {
    let manifest_content = fs::read_to_string(manifest_path)
        .map_err(|error| format!("런타임 manifest를 읽을 수 없습니다: {error}"))?;
    let manifest_content = manifest_content.trim_start_matches('\u{feff}');
    let manifest: RuntimeManifest = serde_json::from_str(manifest_content)
        .map_err(|error| format!("런타임 manifest 형식이 올바르지 않습니다: {error}"))?;
    if manifest.schema_version != MANIFEST_SCHEMA_VERSION {
        return Err(format!(
            "지원하지 않는 런타임 manifest 버전입니다: {}",
            manifest.schema_version
        ));
    }
    if manifest.runtime_version.trim().is_empty() {
        return Err("런타임 버전이 비어 있습니다.".to_owned());
    }
    if manifest.backend.port == 0 || manifest.database.port == 0 {
        return Err("백엔드와 데이터베이스 포트는 0일 수 없습니다.".to_owned());
    }
    if !is_safe_identifier(&manifest.database.name) || !is_safe_identifier(&manifest.database.user)
    {
        return Err("데이터베이스 이름 또는 사용자가 안전하지 않습니다.".to_owned());
    }

    let runtime_dir = manifest_path
        .parent()
        .ok_or_else(|| "런타임 디렉터리를 확인할 수 없습니다.".to_owned())?;
    let java_executable = resolve_runtime_file(runtime_dir, &manifest.java.executable)?;
    let backend_jar = resolve_runtime_file(runtime_dir, &manifest.backend.jar)?;
    let postgres_root = resolve_runtime_directory(runtime_dir, &manifest.database.root)?;
    let ocr_executable_directory = resolve_runtime_directory(
        runtime_dir,
        &manifest.ocr.executable_directory,
    )?;
    let ocr_data_path = resolve_runtime_directory(runtime_dir, &manifest.ocr.data_path)?;

    for required_path in [
        postgres_root.join("bin").join("postgres.exe"),
        postgres_root.join("bin").join("pg_ctl.exe"),
        postgres_root.join("bin").join("initdb.exe"),
        postgres_root.join("bin").join("createdb.exe"),
        postgres_root.join("bin").join("psql.exe"),
        postgres_root
            .join("share")
            .join("extension")
            .join("vector.control"),
        postgres_root.join("lib").join("vector.dll"),
        ocr_executable_directory.join("tesseract.exe"),
        ocr_data_path.join("kor.traineddata"),
        ocr_data_path.join("eng.traineddata"),
    ] {
        if !required_path.is_file() {
            return Err(format!(
                "필수 런타임 파일이 없습니다: {}",
                required_path.display()
            ));
        }
    }
    let vector_sql_directory = postgres_root.join("share").join("extension");
    let has_vector_sql = fs::read_dir(&vector_sql_directory)
        .map_err(|error| format!("pgvector 확장 디렉터리를 읽을 수 없습니다: {error}"))?
        .filter_map(Result::ok)
        .any(|entry| {
            let name = entry.file_name();
            let name = name.to_string_lossy();
            entry.path().is_file() && name.starts_with("vector--") && name.ends_with(".sql")
        });
    if !has_vector_sql {
        return Err("pgvector 확장 SQL 파일이 없습니다.".to_owned());
    }

    Ok(ResolvedRuntime {
        runtime_version: manifest.runtime_version,
        java_executable,
        backend_jar,
        backend_port: manifest.backend.port,
        postgres_root,
        postgres_port: manifest.database.port,
        database_name: manifest.database.name,
        database_user: manifest.database.user,
        ocr_executable_directory,
        ocr_data_path,
    })
}

fn resolve_runtime_file(runtime_dir: &Path, relative: &str) -> Result<PathBuf, String> {
    let path = resolve_inside_runtime(runtime_dir, relative)?;
    if !path.is_file() {
        return Err(format!("런타임 파일이 없습니다: {}", path.display()));
    }
    Ok(path)
}

fn resolve_runtime_directory(runtime_dir: &Path, relative: &str) -> Result<PathBuf, String> {
    let path = resolve_inside_runtime(runtime_dir, relative)?;
    if !path.is_dir() {
        return Err(format!("런타임 디렉터리가 없습니다: {}", path.display()));
    }
    Ok(path)
}

fn resolve_inside_runtime(runtime_dir: &Path, relative: &str) -> Result<PathBuf, String> {
    let relative_path = Path::new(relative);
    if relative_path.is_absolute()
        || relative_path
            .components()
            .any(|component| matches!(component, std::path::Component::ParentDir))
    {
        return Err("런타임 manifest에 허용되지 않은 경로가 있습니다.".to_owned());
    }
    let runtime_canonical = runtime_dir
        .canonicalize()
        .map_err(|error| format!("런타임 경로를 확인할 수 없습니다: {error}"))?;
    let candidate = runtime_dir.join(relative_path);
    let candidate_canonical = candidate
        .canonicalize()
        .map_err(|error| format!("런타임 항목을 확인할 수 없습니다: {error}"))?;
    if !candidate_canonical.starts_with(&runtime_canonical) {
        return Err("런타임 항목이 허용된 디렉터리 밖을 가리킵니다.".to_owned());
    }
    Ok(candidate)
}

fn initialize_postgres(
    runtime: &ResolvedRuntime,
    data_dir: &Path,
    password_file: &Path,
    log_path: &Path,
) -> Result<(), String> {
    if data_dir.join("PG_VERSION").is_file() {
        return Ok(());
    }
    if prepare_postgres_data_directory(data_dir)? {
        write_runtime_log(
            log_path,
            "이전 실행의 불완전한 데이터베이스 초기화 결과를 별도 디렉터리에 보존했습니다.",
        );
    }

    if !password_file.is_file() {
        return Err("데이터베이스 초기 암호 파일을 찾을 수 없습니다.".to_owned());
    }
    let initdb = runtime.postgres_root.join("bin").join("initdb.exe");
    let mut command = hidden_command(&initdb);
    let output = command
        .arg("-D")
        .arg(data_dir)
        .arg("-U")
        .arg(&runtime.database_user)
        .arg(format!("--pwfile={}", password_file.display()))
        .arg("--auth-host=scram-sha-256")
        .arg("--auth-local=scram-sha-256")
        .arg("--encoding=UTF8")
        .arg("--locale=C")
        .arg("--no-instructions")
        .output();
    require_success(output, "PostgreSQL 초기화")?;
    write_runtime_log(
        log_path,
        "애플리케이션 전용 PostgreSQL 데이터 디렉터리를 초기화했습니다.",
    );
    Ok(())
}

fn prepare_postgres_data_directory(data_dir: &Path) -> Result<bool, String> {
    if !data_dir.exists() {
        fs::create_dir_all(data_dir)
            .map_err(|error| format!("데이터베이스 디렉터리를 만들 수 없습니다: {error}"))?;
        return Ok(false);
    }

    let is_empty = fs::read_dir(data_dir)
        .map_err(|error| format!("데이터베이스 디렉터리를 읽을 수 없습니다: {error}"))?
        .next()
        .is_none();
    if is_empty {
        return Ok(false);
    }

    let timestamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|error| format!("복구 디렉터리 시각을 만들 수 없습니다: {error}"))?
        .as_nanos();
    let recovery_name = format!("database-incomplete-{timestamp}");
    let recovery_dir = data_dir.with_file_name(recovery_name);
    fs::rename(data_dir, &recovery_dir).map_err(|error| {
        format!("불완전한 데이터베이스 초기화 결과를 보존할 수 없습니다: {error}")
    })?;
    fs::create_dir_all(data_dir)
        .map_err(|error| format!("데이터베이스 디렉터리를 다시 만들 수 없습니다: {error}"))?;
    Ok(true)
}

fn start_postgres(
    runtime: &mut ResolvedRuntime,
    data_dir: &Path,
    log_path: &Path,
) -> Result<bool, String> {
    let pg_ctl = runtime.postgres_root.join("bin").join("pg_ctl.exe");
    let mut status_command = hidden_command(&pg_ctl);
    if status_command
        .arg("status")
        .arg("-D")
        .arg(data_dir)
        .status()
        .is_ok_and(|status| status.success())
    {
        runtime.postgres_port = read_running_postgres_port(data_dir)?;
        write_runtime_log(
            log_path,
            &format!(
                "이전 실행의 애플리케이션 전용 PostgreSQL을 포트 {}에서 다시 연결했습니다.",
                runtime.postgres_port
            ),
        );
        return Ok(true);
    }

    let selected_postgres_port = select_available_port(
        runtime.postgres_port,
        POSTGRES_FALLBACK_PORT_START..=POSTGRES_FALLBACK_PORT_END,
    )?;
    if selected_postgres_port != runtime.postgres_port {
        write_runtime_log(
            log_path,
            &format!(
                "기본 데이터베이스 포트 {}을 다른 프로그램이 사용 중이어서 {}으로 자동 조정했습니다.",
                runtime.postgres_port, selected_postgres_port
            ),
        );
    }
    runtime.postgres_port = selected_postgres_port;

    let postgres_log = log_path.with_file_name("postgresql.log");
    log_management::prepare_postgres_bootstrap(&postgres_log)
        .map_err(|error| format!("PostgreSQL 시작 로그를 준비하지 못했습니다: {error}"))?;
    let mut command = hidden_command(&pg_ctl);
    let status = command
        .arg("start")
        .arg("-D")
        .arg(data_dir)
        .arg("-l")
        .arg(&postgres_log)
        .arg("-o")
        .arg(postgres_start_options(runtime.postgres_port))
        .arg("-w")
        .arg("-t")
        .arg("30")
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .map_err(|error| format!("PostgreSQL 시작 명령을 실행하지 못했습니다: {error}"))?;
    if !status.success() {
        return Err(format!(
            "PostgreSQL 시작에 실패했습니다(종료 코드 {}). 자세한 내용은 PostgreSQL 로그를 확인하세요.",
            status.code().unwrap_or(-1)
        ));
    }
    write_runtime_log(
        log_path,
        "애플리케이션 전용 PostgreSQL을 루프백 주소에서 시작했습니다.",
    );
    Ok(true)
}

pub(crate) fn postgres_start_options(port: u16) -> String {
    // Relative to this application's database directory; never touches a system PG.
    // 9765 KiB is just below decimal 10 MB. PostgreSQL rotates between records.
    format!("-h 127.0.0.1 -p {port} -c logging_collector=on -c log_destination=stderr \
        -c log_directory=../logs -c log_filename=postgresql-%Y%m%d_%H%M%S.log \
        -c log_rotation_age=1440 -c log_rotation_size=9765 \
        -c log_truncate_on_rotation=off -c log_timezone=UTC")
}

fn ensure_database(runtime: &ResolvedRuntime, password: &str) -> Result<(), String> {
    let psql = runtime.postgres_root.join("bin").join("psql.exe");
    let mut check_command = database_command(&psql, runtime, password);
    let output = check_command
        .arg("--dbname=postgres")
        .arg("--tuples-only")
        .arg("--no-align")
        .arg("--command")
        .arg(format!(
            "SELECT 1 FROM pg_database WHERE datname = '{}';",
            runtime.database_name
        ))
        .output()
        .map_err(|error| format!("데이터베이스 존재 여부를 확인할 수 없습니다: {error}"))?;
    if !output.status.success() {
        return Err(command_failure("데이터베이스 존재 확인", &output));
    }
    if String::from_utf8_lossy(&output.stdout).trim() == "1" {
        return Ok(());
    }

    let createdb = runtime.postgres_root.join("bin").join("createdb.exe");
    let mut create_command = database_command(&createdb, runtime, password);
    let output = create_command
        .arg("--owner")
        .arg(&runtime.database_user)
        .arg(&runtime.database_name)
        .output();
    require_success(output, "PrivateKB 데이터베이스 생성")
}

fn start_backend(
    runtime: &ResolvedRuntime,
    app_data_dir: &Path,
    password: &str,
    shutdown_token: &str,
    bridge_token: &str,
) -> Result<Child, String> {
    if !is_port_available(runtime.backend_port) {
        return Err(format!(
            "백엔드 포트 {}를 다른 프로세스가 사용 중입니다.",
            runtime.backend_port
        ));
    }

    let logs_dir = app_data_dir.join("logs");
    let backend_log_path = logs_dir.join("backend.log");

    let mut command = hidden_command(&runtime.java_executable);
    command
        .arg("-Djava.awt.headless=true")
        .arg("-jar")
        .arg(&runtime.backend_jar)
        .current_dir(app_data_dir)
        .env(
            "PRIVATEKB_DB_URL",
            format!(
                "jdbc:postgresql://127.0.0.1:{}/{}",
                runtime.postgres_port, runtime.database_name
            ),
        )
        .env("PRIVATEKB_DB_USER", &runtime.database_user)
        .env("PRIVATEKB_DB_PASSWORD", password)
        .env("PRIVATEKB_STORAGE_ROOT", app_data_dir.join("storage"))
        .env("PRIVATEKB_OCR_ENABLED", "true")
        .env(
            "PRIVATEKB_OCR_EXECUTABLE_DIRECTORY",
            &runtime.ocr_executable_directory,
        )
        .env("PRIVATEKB_OCR_DATA_PATH", &runtime.ocr_data_path)
        .env("OMP_THREAD_LIMIT", "1")
        .env("PRIVATEKB_DESKTOP_LIFECYCLE_ENABLED", "true")
        .env("PRIVATEKB_DESKTOP_SHUTDOWN_TOKEN", shutdown_token)
        .env("PRIVATEKB_DESKTOP_BRIDGE_ENABLED", "true")
        .env("PRIVATEKB_DESKTOP_BRIDGE_TOKEN", bridge_token)
        .env("SERVER_PORT", runtime.backend_port.to_string())
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    let mut child = command
        .spawn()
        .map_err(|error| format!("Spring Boot 백엔드를 시작하지 못했습니다: {error}"))?;
    let captures = (|| -> std::io::Result<()> {
        log_management::capture(child.stdout.take().expect("piped stdout"), backend_log_path.clone())?;
        log_management::capture(child.stderr.take().expect("piped stderr"), backend_log_path)?;
        Ok(())
    })();
    if let Err(error) = captures {
        let _ = child.kill();
        let _ = wait_for_child_exit(&mut child, Duration::from_secs(2));
        return Err(format!("백엔드 로그 수집을 시작하지 못했습니다: {error}"));
    }
    Ok(child)
}

fn wait_for_backend(
    child: &mut Child,
    port: u16,
    stop_requested: &AtomicBool,
) -> Result<(), String> {
    let deadline = Instant::now() + BACKEND_READY_TIMEOUT;
    while Instant::now() < deadline {
        if stop_requested.load(Ordering::SeqCst) {
            return Err("애플리케이션 종료로 백엔드 준비 확인을 취소했습니다.".to_owned());
        }
        if let Some(status) = child
            .try_wait()
            .map_err(|error| format!("백엔드 프로세스 상태를 확인하지 못했습니다: {error}"))?
        {
            return Err(format!(
                "백엔드가 준비 전에 종료되었습니다(종료 코드 {}).",
                status.code().unwrap_or(-1)
            ));
        }
        if backend_is_ready(port) {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(500));
    }
    Err("Spring Boot 백엔드가 90초 안에 준비되지 않았습니다.".to_owned())
}

fn backend_is_ready(port: u16) -> bool {
    let address = SocketAddrV4::new(Ipv4Addr::LOCALHOST, port);
    let Ok(mut stream) = TcpStream::connect_timeout(&address.into(), Duration::from_millis(500))
    else {
        return false;
    };
    let _ = stream.set_read_timeout(Some(Duration::from_secs(1)));
    let request = format!(
        "GET /actuator/health HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nConnection: close\r\n\r\n"
    );
    if stream.write_all(request.as_bytes()).is_err() {
        return false;
    }
    let mut response = Vec::with_capacity(1024);
    if stream.read_to_end(&mut response).is_err() {
        return false;
    }
    let text = String::from_utf8_lossy(&response);
    text.starts_with("HTTP/1.1 200") && text.contains("\"status\":\"UP\"")
}

fn request_backend_shutdown(port: u16, token: &str) -> Result<(), String> {
    if token.is_empty() {
        return Err("백엔드 종료 토큰이 없습니다.".to_owned());
    }
    let address = SocketAddrV4::new(Ipv4Addr::LOCALHOST, port);
    let mut stream = TcpStream::connect_timeout(&address.into(), Duration::from_secs(1))
        .map_err(|error| format!("백엔드 종료 API에 연결하지 못했습니다: {error}"))?;
    let _ = stream.set_read_timeout(Some(Duration::from_secs(2)));
    let request = format!(
        "POST /api/desktop/shutdown HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nX-PrivateKB-Shutdown-Token: {token}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    );
    stream
        .write_all(request.as_bytes())
        .map_err(|error| format!("백엔드 종료 요청을 보내지 못했습니다: {error}"))?;
    let mut response = [0_u8; 128];
    let count = stream
        .read(&mut response)
        .map_err(|error| format!("백엔드 종료 응답을 읽지 못했습니다: {error}"))?;
    let status_line = String::from_utf8_lossy(&response[..count]);
    if status_line.starts_with("HTTP/1.1 202") {
        Ok(())
    } else {
        Err("백엔드가 안전 종료 요청을 거부했습니다.".to_owned())
    }
}

fn database_command(program: &Path, runtime: &ResolvedRuntime, password: &str) -> Command {
    let mut command = hidden_command(program);
    command
        .arg("--host=127.0.0.1")
        .arg(format!("--port={}", runtime.postgres_port))
        .arg(format!("--username={}", runtime.database_user))
        .env("PGPASSWORD", password);
    command
}

fn hidden_command(program: &Path) -> Command {
    let mut command = Command::new(program);
    #[cfg(windows)]
    command.creation_flags(CREATE_NO_WINDOW);
    command
}

fn require_success(output: std::io::Result<Output>, action: &str) -> Result<(), String> {
    let output = output.map_err(|error| format!("{action} 명령을 실행하지 못했습니다: {error}"))?;
    if output.status.success() {
        Ok(())
    } else {
        Err(command_failure(action, &output))
    }
}

fn command_failure(action: &str, output: &Output) -> String {
    let detail = String::from_utf8_lossy(&output.stderr);
    let sanitized = detail.lines().take(3).collect::<Vec<_>>().join(" ");
    format!(
        "{action}에 실패했습니다(종료 코드 {}): {}",
        output.status.code().unwrap_or(-1),
        sanitized.trim()
    )
}

fn read_running_postgres_port(data_dir: &Path) -> Result<u16, String> {
    let pid_path = data_dir.join("postmaster.pid");
    let content = fs::read_to_string(&pid_path)
        .map_err(|error| format!("실행 중인 PostgreSQL 연결 정보를 읽을 수 없습니다: {error}"))?;
    let value = content
        .lines()
        .nth(3)
        .ok_or_else(|| "실행 중인 PostgreSQL 연결 정보에 포트가 없습니다.".to_owned())?;
    value
        .trim()
        .parse::<u16>()
        .ok()
        .filter(|port| *port > 0)
        .ok_or_else(|| "실행 중인 PostgreSQL 연결 포트가 올바르지 않습니다.".to_owned())
}

fn select_available_port(
    preferred: u16,
    fallback_ports: std::ops::RangeInclusive<u16>,
) -> Result<u16, String> {
    select_port_with(
        std::iter::once(preferred).chain(fallback_ports),
        is_port_available,
    )
    .ok_or_else(|| "PrivateKB가 사용할 수 있는 로컬 연결 포트를 찾지 못했습니다.".to_owned())
}

fn select_port_with<I, F>(ports: I, mut is_available: F) -> Option<u16>
where
    I: IntoIterator<Item = u16>,
    F: FnMut(u16) -> bool,
{
    ports
        .into_iter()
        .find(|port| *port > 0 && is_available(*port))
}

fn is_port_available(port: u16) -> bool {
    let address = SocketAddrV4::new(Ipv4Addr::LOCALHOST, port);
    TcpListener::bind(address).is_ok()
}

fn wait_for_child_exit(child: &mut Child, timeout: Duration) -> bool {
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        match child.try_wait() {
            Ok(Some(_)) => return true,
            Ok(None) => thread::sleep(Duration::from_millis(100)),
            Err(_) => return false,
        }
    }
    false
}

fn load_or_create_secret(path: &Path) -> Result<String, String> {
    if path.is_file() {
        let value = fs::read_to_string(path)
            .map_err(|error| format!("데이터베이스 암호를 읽을 수 없습니다: {error}"))?;
        let trimmed = value.trim();
        if trimmed.len() < 64 || !trimmed.bytes().all(|byte| byte.is_ascii_hexdigit()) {
            return Err("저장된 데이터베이스 암호 형식이 올바르지 않습니다.".to_owned());
        }
        return Ok(trimmed.to_owned());
    }

    let secret = generate_secret()?;
    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(path)
        .map_err(|error| format!("데이터베이스 암호 파일을 만들 수 없습니다: {error}"))?;
    file.write_all(secret.as_bytes())
        .map_err(|error| format!("데이터베이스 암호를 저장할 수 없습니다: {error}"))?;
    file.sync_all()
        .map_err(|error| format!("데이터베이스 암호를 디스크에 반영할 수 없습니다: {error}"))?;
    Ok(secret)
}

fn generate_secret() -> Result<String, String> {
    let mut bytes = [0_u8; 48];
    getrandom(&mut bytes).map_err(|error| format!("안전한 난수를 생성할 수 없습니다: {error}"))?;
    let mut value = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        use std::fmt::Write as _;
        let _ = write!(&mut value, "{byte:02x}");
    }
    Ok(value)
}

fn is_safe_identifier(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 63
        && value
            .bytes()
            .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'_')
}

fn windows_process_path(path: PathBuf) -> PathBuf {
    #[cfg(windows)]
    {
        let text = path.to_string_lossy();
        if let Some(stripped) = text.strip_prefix(r"\\?\UNC\") {
            return PathBuf::from(format!(r"\\{stripped}"));
        }
        if let Some(stripped) = text.strip_prefix(r"\\?\") {
            return PathBuf::from(stripped);
        }
    }
    path
}

fn write_runtime_log(path: &Path, message: &str) {
    log_management::runtime(path, "INFO", message);
}

fn lock_or_recover<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

#[cfg(test)]
mod tests {
    #[test]
    #[ignore = "격리된 동봉 런타임의 실제 로그 회전·종료 검사"]
    fn managed_log_smoke() {
        use super::*;
        let root = PathBuf::from(std::env::var("PRIVATEKB_LOG_SMOKE_ROOT").expect("isolated test root"));
        let manifest = PathBuf::from(std::env::var("PRIVATEKB_LOG_SMOKE_MANIFEST").expect("staged runtime manifest"));
        assert!(!root.exists(), "Test root must be new");
        fs::create_dir_all(root.join("logs")).unwrap();
        let log = root.join("logs/runtime.log");
        let maintenance = log_management::Maintenance::start(root.join("logs")).unwrap();
        let mut processes = start_managed_runtime(&manifest, &root, &log, &AtomicBool::new(false)).unwrap();
        // Always stop only this test's processes, including when an assertion fails.
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let config = load_runtime(&manifest).unwrap();
            let port = read_running_postgres_port(&root.join("database")).unwrap();
            let password = fs::read_to_string(root.join("config/database-password")).unwrap();
            let query = |sql: &str| {
                let output = hidden_command(&config.postgres_root.join("bin/psql.exe"))
                    .args(["-X", "-w", "-h", "127.0.0.1", "-p", &port.to_string(), "-U", &config.database_user,
                        "-d", &config.database_name, "-At", "-v", "ON_ERROR_STOP=1", "-c", sql])
                    .env("PGPASSWORD", password.trim()).env_remove("PGOPTIONS")
                    .output().unwrap();
                assert!(output.status.success(), "Test SQL failed");
                String::from_utf8_lossy(&output.stdout).trim().to_owned()
            };
            assert_eq!(query("SELECT setting FROM pg_settings WHERE name='logging_collector'"), "on");
            assert_eq!(query("SELECT setting FROM pg_settings WHERE name='log_rotation_size'"), "9765");
            assert_eq!(query("SELECT setting FROM pg_settings WHERE name='log_rotation_age'"), "1440");
            assert_eq!(query("SHOW log_directory"), "../logs");
            assert!(root.join("logs/backend.log").metadata().unwrap().len() > 0);
            assert!(fs::read_to_string(&log).unwrap().contains("[INFO]"));
            // Synthetic log payload only; no document contents or model requests.
            for _ in 0..3 {
                query("DO $$ BEGIN FOR i IN 1..5000 LOOP RAISE LOG '%', repeat('log-test-',100); END LOOP; END $$");
                thread::sleep(Duration::from_millis(1100));
            }
            let archives = fs::read_dir(root.join("logs")).unwrap().filter_map(Result::ok)
                .filter(|e| e.file_name().to_string_lossy().starts_with("postgresql-")).count();
            assert!(archives >= 2, "Native PG size rotation did not create another file");
            assert!(root.join("database/current_logfiles").exists());
        }));
        processes.shutdown(&log);
        drop(maintenance);
        assert!(!root.join("database/postmaster.pid").exists());
        // Do not leave even the synthetic DB credential in the build report folder.
        fs::remove_file(root.join("config/database-password")).unwrap();
        if let Err(payload) = result { std::panic::resume_unwind(payload); }
    }

    use super::{
        generate_secret, is_safe_identifier, load_or_create_secret, load_runtime,
        prepare_postgres_data_directory, read_running_postgres_port, select_port_with,
        windows_process_path, RuntimePhase, RuntimeSupervisor,
    };
    use std::fs;
    use std::path::{Path, PathBuf};
    use std::time::{SystemTime, UNIX_EPOCH};

    fn test_directory(label: &str) -> PathBuf {
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("시각 확인")
            .as_nanos();
        std::env::temp_dir().join(format!("privatekb-{label}-{unique}"))
    }

    fn write_test_runtime(directory: &Path, java_path: &str) {
        for relative in [
            "java/bin",
            "app",
            "postgresql/bin",
            "postgresql/lib",
            "postgresql/share/extension",
            "ocr/tessdata",
        ] {
            fs::create_dir_all(directory.join(relative)).expect("시험 런타임 디렉터리 생성");
        }
        for relative in [
            "java/bin/java.exe",
            "app/privatekb.jar",
            "postgresql/bin/postgres.exe",
            "postgresql/bin/pg_ctl.exe",
            "postgresql/bin/initdb.exe",
            "postgresql/bin/createdb.exe",
            "postgresql/bin/psql.exe",
            "postgresql/lib/vector.dll",
            "postgresql/share/extension/vector.control",
            "postgresql/share/extension/vector--0.8.6.sql",
            "ocr/tesseract.exe",
            "ocr/tessdata/kor.traineddata",
            "ocr/tessdata/eng.traineddata",
        ] {
            fs::write(directory.join(relative), b"test").expect("시험 런타임 파일 생성");
        }
        let manifest = format!(
            r#"{{
                "schemaVersion": 2,
                "runtimeVersion": "test",
                "java": {{ "executable": "{java_path}" }},
                "backend": {{ "jar": "app/privatekb.jar", "port": 8080 }},
                "database": {{
                    "root": "postgresql",
                    "port": 5433,
                    "name": "privatekb",
                    "user": "privatekb"
                }},
                "ocr": {{
                    "executableDirectory": "ocr",
                    "dataPath": "ocr/tessdata"
                }}
            }}"#
        );
        fs::write(directory.join("manifest.json"), manifest).expect("시험 manifest 생성");
    }

    #[test]
    fn runtime_supervisor_starts_in_explicit_starting_state() {
        let supervisor = RuntimeSupervisor::new();

        assert_eq!(supervisor.status().phase, RuntimePhase::Starting);
        assert_eq!(supervisor.status().backend_port, None);
        assert!(supervisor.status().error_code.is_none());
    }

    #[test]
    fn port_selection_prefers_configured_port_when_available() {
        let selected = select_port_with([8080, 18080, 18081], |port| port == 8080);

        assert_eq!(selected, Some(8080));
    }

    #[test]
    fn port_selection_skips_occupied_ports_and_uses_first_fallback() {
        let selected = select_port_with([5433, 15433, 15434], |port| port == 15434);

        assert_eq!(selected, Some(15434));
    }

    #[test]
    fn running_postgres_port_is_read_from_its_pid_file() {
        let directory = test_directory("runtime-postgres-port-test");
        fs::create_dir_all(&directory).expect("시험 데이터베이스 디렉터리 생성");
        fs::write(
            directory.join("postmaster.pid"),
            "1234\nC:/PrivateKB/database\n1788050400\n15433\n\n127.0.0.1\n",
        )
        .expect("시험 연결 정보 생성");

        assert_eq!(read_running_postgres_port(&directory), Ok(15433));
        fs::remove_dir_all(directory).expect("시험 디렉터리 삭제");
    }

    #[test]
    fn generated_secret_is_long_hex_value() {
        let secret = generate_secret().expect("난수 생성 성공");
        assert_eq!(secret.len(), 96);
        assert!(secret.bytes().all(|byte| byte.is_ascii_hexdigit()));
    }

    #[test]
    fn stored_secret_is_reused() {
        let directory = test_directory("runtime-secret-test");
        fs::create_dir_all(&directory).expect("시험 디렉터리 생성");
        let path = directory.join("database-password");

        let first = load_or_create_secret(&path).expect("암호 생성");
        let second = load_or_create_secret(&path).expect("암호 재사용");

        assert_eq!(first, second);
        fs::remove_dir_all(directory).expect("시험 디렉터리 삭제");
    }

    #[test]
    fn database_identifier_rejects_unsafe_characters() {
        assert!(is_safe_identifier("privatekb_01"));
        assert!(!is_safe_identifier("privatekb;drop"));
        assert!(!is_safe_identifier("PrivateKB"));
        assert!(!is_safe_identifier(""));
    }

    #[test]
    fn windows_verbatim_path_is_converted_for_child_processes() {
        let converted = windows_process_path(PathBuf::from(r"\\?\C:\PrivateKB\runtime"));

        assert_eq!(converted, PathBuf::from(r"C:\PrivateKB\runtime"));
    }

    #[test]
    fn incomplete_database_directory_is_preserved_before_retry() {
        let directory = test_directory("runtime-database-recovery-test");
        let database = directory.join("database");
        fs::create_dir_all(&database).expect("시험 데이터베이스 디렉터리 생성");
        fs::write(database.join("partial-file"), b"partial").expect("불완전 파일 생성");

        let recovered = prepare_postgres_data_directory(&database).expect("불완전 초기화 복구");

        assert!(recovered);
        assert!(database.is_dir());
        assert_eq!(
            fs::read_dir(&database).expect("새 디렉터리 읽기").count(),
            0
        );
        let preserved = fs::read_dir(&directory)
            .expect("복구 디렉터리 읽기")
            .filter_map(Result::ok)
            .find(|entry| {
                entry
                    .file_name()
                    .to_string_lossy()
                    .starts_with("database-incomplete-")
            })
            .expect("보존된 불완전 디렉터리");
        assert!(preserved.path().join("partial-file").is_file());
        fs::remove_dir_all(directory).expect("시험 디렉터리 삭제");
    }

    #[test]
    fn complete_runtime_manifest_is_resolved() {
        let directory = test_directory("runtime-manifest-test");
        write_test_runtime(&directory, "java/bin/java.exe");

        let runtime = load_runtime(&directory.join("manifest.json")).expect("런타임 확인");

        assert_eq!(runtime.backend_port, 8080);
        assert_eq!(runtime.postgres_port, 5433);
        assert_eq!(runtime.database_name, "privatekb");
        assert!(!runtime
            .java_executable
            .to_string_lossy()
            .starts_with(r"\\?\"));
        fs::remove_dir_all(directory).expect("시험 디렉터리 삭제");
    }

    #[test]
    fn runtime_manifest_rejects_parent_path() {
        let directory = test_directory("runtime-path-test");
        write_test_runtime(&directory, "../java.exe");

        let error = load_runtime(&directory.join("manifest.json")).expect_err("상위 경로 거부");

        assert!(error.contains("허용되지 않은 경로"));
        fs::remove_dir_all(directory).expect("시험 디렉터리 삭제");
    }

    #[test]
    fn runtime_manifest_accepts_utf8_bom_from_windows_powershell() {
        let directory = test_directory("runtime-bom-test");
        write_test_runtime(&directory, "java/bin/java.exe");
        let manifest_path = directory.join("manifest.json");
        let content = fs::read(&manifest_path).expect("manifest 읽기");
        let mut content_with_bom = vec![0xef, 0xbb, 0xbf];
        content_with_bom.extend(content);
        fs::write(&manifest_path, content_with_bom).expect("BOM manifest 쓰기");

        let runtime = load_runtime(&manifest_path).expect("BOM manifest 확인");

        assert_eq!(runtime.runtime_version, "test");
        fs::remove_dir_all(directory).expect("시험 디렉터리 삭제");
    }
}
