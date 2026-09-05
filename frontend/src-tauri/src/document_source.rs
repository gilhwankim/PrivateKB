use crate::document_import::{has_supported_extension, is_local_path, is_reparse_or_symlink, windows_user_path};
use crate::runtime::RuntimeSupervisor;
use serde::{Deserialize, Serialize};
use std::fs;
use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddrV4, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant, UNIX_EPOCH};
use tauri::State;

const WORKSPACE: &str = "00000000-0000-0000-0000-000000000001";
const MAX_RESPONSE_BYTES: u64 = 1024 * 1024;
static REVEAL_BUSY: AtomicBool = AtomicBool::new(false);

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct SourceLocation {
    path: String,
    byte_size: u64,
    last_modified_millis: u64,
}

#[derive(Deserialize)]
struct SourceLocations {
    locations: Vec<SourceLocation>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct RevealSourceResult {
    source_changed: bool,
}

struct RevealGuard;
impl Drop for RevealGuard {
    fn drop(&mut self) {
        REVEAL_BUSY.store(false, Ordering::Release);
    }
}

#[tauri::command]
pub(crate) async fn reveal_document_source(
    document_version_id: String,
    runtime: State<'_, RuntimeSupervisor>,
) -> Result<RevealSourceResult, String> {
    if !valid_version_id(&document_version_id) {
        return Err("SOURCE_UNAVAILABLE".into());
    }
    let bridge = runtime.bridge_connection().map_err(|_| "APP_NOT_READY")?;
    REVEAL_BUSY.compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
        .map_err(|_| "REVEAL_BUSY")?;
    let guard = RevealGuard;
    // 파일 시스템 확인과 Windows Shell 호출은 화면 스레드 밖에서 수행한다.
    tauri::async_runtime::spawn_blocking(move || {
        let _guard = guard;
        reveal_with(bridge.backend_port, &bridge.token, &document_version_id, show_in_explorer)
            .map_err(str::to_owned)
    }).await.map_err(|_| "REVEAL_FAILED".to_owned())?
}

fn valid_version_id(value: &str) -> bool {
    value.len() == 36 && value.bytes().enumerate().all(|(index, byte)| {
        if [8, 13, 18, 23].contains(&index) { byte == b'-' } else { byte.is_ascii_hexdigit() }
    })
}

fn reveal_with(
    port: u16, token: &str, version: &str,
    open: impl FnOnce(&Path) -> Result<(), &'static str>,
) -> Result<RevealSourceResult, &'static str> {
    let locations = load_locations(port, token, version)?;
    let (path, source_changed) = choose_source(&locations.locations)?;
    open(&path)?;
    Ok(RevealSourceResult { source_changed })
}

fn load_locations(port: u16, token: &str, version: &str) -> Result<SourceLocations, &'static str> {
    if !valid_version_id(version) || token.is_empty() || token.len() > 256
        || !token.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_') {
        return Err("APP_NOT_READY");
    }
    let address = SocketAddrV4::new(Ipv4Addr::LOCALHOST, port);
    let mut stream = TcpStream::connect_timeout(&address.into(), Duration::from_secs(2))
        .map_err(|_| "APP_NOT_READY")?;
    stream.set_read_timeout(Some(Duration::from_secs(5))).map_err(|_| "SOURCE_UNAVAILABLE")?;
    stream.set_write_timeout(Some(Duration::from_secs(2))).map_err(|_| "SOURCE_UNAVAILABLE")?;
    // HTTP/1.0 + close로 길이가 제한된 JSON만 받는다. 리디렉션·압축 응답은 허용하지 않는다.
    let request = format!(
        "POST /api/desktop/workspaces/{WORKSPACE}/document-versions/{version}/source-locations HTTP/1.0\r\nHost: 127.0.0.1:{port}\r\nX-PrivateKB-Desktop-Token: {token}\r\nAccept: application/json\r\nAccept-Encoding: identity\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    );
    stream.write_all(request.as_bytes()).map_err(|_| "SOURCE_UNAVAILABLE")?;
    let mut response = Vec::new();
    let deadline = Instant::now() + Duration::from_secs(5);
    let mut buffer = [0_u8; 4096];
    loop {
        let remaining = deadline.checked_duration_since(Instant::now()).ok_or("SOURCE_UNAVAILABLE")?;
        stream.set_read_timeout(Some(remaining)).map_err(|_| "SOURCE_UNAVAILABLE")?;
        let size = stream.read(&mut buffer).map_err(|_| "SOURCE_UNAVAILABLE")?;
        if size == 0 { break; }
        response.extend_from_slice(&buffer[..size]);
        if response.len() as u64 > MAX_RESPONSE_BYTES { return Err("SOURCE_UNAVAILABLE"); }
    }
    parse_response(&response)
}

fn parse_response(response: &[u8]) -> Result<SourceLocations, &'static str> {
    if response.len() as u64 > MAX_RESPONSE_BYTES { return Err("SOURCE_UNAVAILABLE"); }
    let split = response.windows(4).position(|w| w == b"\r\n\r\n").ok_or("SOURCE_UNAVAILABLE")?;
    let headers = std::str::from_utf8(&response[..split]).map_err(|_| "SOURCE_UNAVAILABLE")?;
    let mut lines = headers.split("\r\n");
    let mut status = lines.next().unwrap_or_default().split_whitespace();
    if !matches!(status.next(), Some("HTTP/1.0" | "HTTP/1.1")) || status.next() != Some("200") {
        return Err("SOURCE_UNAVAILABLE");
    }
    let body = &response[split + 4..];
    let mut json = false;
    for line in lines {
        let (name, value) = line.split_once(':').ok_or("SOURCE_UNAVAILABLE")?;
        let value = value.trim();
        if name.eq_ignore_ascii_case("transfer-encoding") || name.eq_ignore_ascii_case("content-encoding") {
            return Err("SOURCE_UNAVAILABLE");
        }
        if name.eq_ignore_ascii_case("content-type") {
            json = value.split(';').next().is_some_and(|v| v.trim().eq_ignore_ascii_case("application/json"));
        }
        if name.eq_ignore_ascii_case("content-length") && value.parse::<usize>().ok() != Some(body.len()) {
            return Err("SOURCE_UNAVAILABLE");
        }
    }
    if !json { return Err("SOURCE_UNAVAILABLE"); }
    let result: SourceLocations = serde_json::from_slice(body).map_err(|_| "SOURCE_UNAVAILABLE")?;
    if result.locations.len() > 8 { return Err("SOURCE_UNAVAILABLE"); }
    Ok(result)
}

fn choose_source(locations: &[SourceLocation]) -> Result<(PathBuf, bool), &'static str> {
    if locations.is_empty() { return Err("SOURCE_NOT_RECORDED"); }
    let mut failure = "SOURCE_MISSING";
    for location in locations.iter().take(8) {
        match validate_source(&location.path) {
            Ok((path, metadata)) => {
                let modified = metadata.modified().ok().and_then(|v| v.duration_since(UNIX_EPOCH).ok())
                    .map(|v| v.as_millis());
                let changed = metadata.len() != location.byte_size
                    || modified != Some(u128::from(location.last_modified_millis));
                return Ok((path, changed));
            }
            Err(error) => {
                if error != "SOURCE_MISSING" { failure = error; }
            }
        }
    }
    Err(failure)
}

fn safe_windows_syntax(value: &str) -> bool {
    let bytes = value.as_bytes();
    if bytes.len() < 4 || !bytes[0].is_ascii_alphabetic() || bytes[1] != b':' || bytes[2] != b'\\'
        || value.encode_utf16().count() > 32760 {
        return false;
    }
    value[3..].split('\\').all(|part| {
        let stem = part.split('.').next().unwrap_or_default().to_ascii_uppercase();
        !part.is_empty() && part != "." && part != ".." && !part.ends_with(['.', ' '])
            && !part.chars().any(|c| c.is_control() || ":/\"<>|?*".contains(c))
            && !matches!(stem.as_str(), "CON" | "PRN" | "AUX" | "NUL")
            && !(stem.len() == 4 && (stem.starts_with("COM") || stem.starts_with("LPT"))
                && stem.as_bytes()[3].is_ascii_digit())
    })
}

fn io_code(error: std::io::Error) -> &'static str {
    if error.kind() == std::io::ErrorKind::NotFound { "SOURCE_MISSING" } else { "SOURCE_UNAVAILABLE" }
}

fn validate_source(value: &str) -> Result<(PathBuf, fs::Metadata), &'static str> {
    if !safe_windows_syntax(value) { return Err("SOURCE_UNSAFE"); }
    let path = Path::new(value);
    if !has_supported_extension(path) || !is_local_path(path) { return Err("SOURCE_UNSAFE"); }
    // 존재 검사 전에 네트워크/장치 경로를 거부하고 상위 폴더의 정션도 검사한다.
    let mut current = PathBuf::new();
    for component in path.components() {
        current.push(component);
        if current.parent().is_none() && !current.has_root() { continue; }
        let metadata = fs::symlink_metadata(&current).map_err(io_code)?;
        if is_reparse_or_symlink(&metadata) { return Err("SOURCE_UNSAFE"); }
    }
    let metadata = fs::symlink_metadata(path).map_err(io_code)?;
    if !metadata.is_file() { return Err("SOURCE_UNSAFE"); }
    let canonical = windows_user_path(path.canonicalize().map_err(io_code)?);
    if !is_local_path(&canonical) { return Err("SOURCE_UNSAFE"); }
    Ok((canonical, metadata))
}

#[cfg(windows)]
fn show_in_explorer(path: &Path) -> Result<(), &'static str> {
    use std::os::windows::ffi::OsStrExt;
    use std::ptr::{null, null_mut};
    use windows_sys::Win32::System::Com::{CoInitializeEx, CoTaskMemFree, CoUninitialize, COINIT_APARTMENTTHREADED};
    use windows_sys::Win32::UI::Shell::{SHOpenFolderAndSelectItems, SHParseDisplayName};
    let wide: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
    unsafe {
        if CoInitializeEx(null(), COINIT_APARTMENTTHREADED as u32) < 0 { return Err("REVEAL_FAILED"); }
        let mut pidl = null_mut();
        let parsed = SHParseDisplayName(wide.as_ptr(), null_mut(), &mut pidl, 0, null_mut());
        // PIDL을 사용하므로 명령 문자열, cmd.exe, explorer.exe 인자 파싱을 거치지 않는다.
        let result = if parsed >= 0 && !pidl.is_null() {
            SHOpenFolderAndSelectItems(pidl, 0, null(), 0)
        } else { -1 };
        if !pidl.is_null() { CoTaskMemFree(pidl.cast()); }
        CoUninitialize();
        if result < 0 { return Err("REVEAL_FAILED"); }
    }
    Ok(())
}

#[cfg(not(windows))]
fn show_in_explorer(_: &Path) -> Result<(), &'static str> { Err("SOURCE_UNAVAILABLE") }

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    #[ignore = "독립 DB와 임시 데스크톱 세션으로 수행하는 통합 검사"]
    fn bridge_smoke() {
        let port = std::env::var("PRIVATEKB_REVEAL_SMOKE_PORT").unwrap().parse().unwrap();
        let token = std::env::var("PRIVATEKB_REVEAL_SMOKE_TOKEN").unwrap();
        let version = std::env::var("PRIVATEKB_REVEAL_SMOKE_VERSION").unwrap();
        let expected = PathBuf::from(std::env::var("PRIVATEKB_REVEAL_SMOKE_FILE").unwrap());
        let result = reveal_with(port, &token, &version, |actual| {
            assert_eq!(actual, windows_user_path(expected.canonicalize().unwrap()));
            Ok(())
        }).expect("네이티브 원본 조회 연결 실패");
        assert!(!result.source_changed);
        let private_response = load_locations(port, &token, &version).unwrap();
        assert_eq!(private_response.locations.len(), 1, "다른 버전 위치가 섞이면 안 됩니다");
        assert!(matches!(load_locations(port, "wrong-token", &version), Err("SOURCE_UNAVAILABLE")));
        assert!(matches!(reveal_with(port, &token, "90000000-0000-0000-0000-000000000009", |_| panic!("원본 없는 문서를 열면 안 됩니다")), Err("SOURCE_NOT_RECORDED")));
    }

    #[test]
    fn validates_version_id_before_http_request() {
        assert!(valid_version_id(WORKSPACE));
        for value in ["../secret", "\r\nHost: remote", "a" , "00000000-0000-0000-0000-00000000000z"] {
            assert!(!valid_version_id(value));
        }
    }

    #[test]
    fn rejects_network_devices_traversal_ads_and_shell_names() {
        for path in [r"\\server\share\file.txt", r"\\?\C:\file.txt", r"\\.\pipe\name", "shell:desktop",
            r"C:\a\..\file.txt", r"C:\a\.\file.txt", r"C:\a\file.txt:stream", r"C:\a\NUL.txt",
            r"C:\a\CON", r"C:\a\COM1.txt", r"C:\a\file.txt.", "C:\\a\\file.txt\r\n", "C:relative.txt"] {
            assert!(!safe_windows_syntax(path), "unsafe syntax accepted");
        }
        assert!(safe_windows_syntax(r"C:\업무 자료\회의록, 검토 & 결정.txt"));
    }

    #[test]
    fn handles_private_http_without_exposing_error_body() {
        let parsed = parse_response(b"HTTP/1.0 200 OK\r\nContent-Type: application/json\r\n\r\n{\"locations\":[]}").unwrap();
        assert!(parsed.locations.is_empty());
        for response in [
            "HTTP/1.0 403 Forbidden\r\n\r\nC:\\private-canary.txt",
            "HTTP/1.0 302 Found\r\nLocation: https://example.invalid\r\n\r\n",
            "HTTP/1.0 200 OK\r\nContent-Type: text/html\r\n\r\n{\"locations\":[]}",
            "HTTP/1.0 200 OK\r\nContent-Type: application/json\r\nContent-Length: 999\r\n\r\n{\"locations\":[]}",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n",
        ] {
            assert!(matches!(parse_response(response.as_bytes()), Err("SOURCE_UNAVAILABLE")));
        }
        assert!(matches!(parse_response(&vec![b'x'; MAX_RESPONSE_BYTES as usize + 1]), Err("SOURCE_UNAVAILABLE")));
    }

    #[test]
    fn distinguishes_unrecorded_and_missing_sources() {
        assert!(matches!(choose_source(&[]), Err("SOURCE_NOT_RECORDED")));
        let path = std::env::temp_dir().join(format!("privatekb-missing-{}.txt", std::process::id()));
        assert!(matches!(validate_source(path.to_str().unwrap()), Err("SOURCE_MISSING")));
    }

    #[test]
    fn reveals_selected_existing_copy_without_reading_contents() {
        let path = std::env::temp_dir().join(format!("privatekb-reveal-{}, 한글 & 자료.txt", std::process::id()));
        fs::write(&path, "합성 문서").unwrap();
        let metadata = fs::metadata(&path).unwrap();
        let source = SourceLocation {
            path: path.to_str().unwrap().into(), byte_size: metadata.len(),
            last_modified_millis: metadata.modified().unwrap().duration_since(UNIX_EPOCH).unwrap().as_millis() as u64,
        };
        let missing = SourceLocation { path: path.with_file_name("privatekb-absent-example.txt").to_str().unwrap().into(), byte_size: 1, last_modified_millis: 0 };
        let (selected, changed) = choose_source(&[missing, source]).unwrap();
        assert_eq!(selected, windows_user_path(path.canonicalize().unwrap()));
        assert!(!changed);
        let changed_source = SourceLocation { path: path.to_str().unwrap().into(), byte_size: 1, last_modified_millis: 0 };
        assert!(choose_source(&[changed_source]).unwrap().1);
        fs::remove_file(path).unwrap();
    }

    #[test]
    #[ignore = "탐색기를 여는 수동 승인 기동 검사"]
    fn explorer_smoke() {
        let value = std::env::var("PRIVATEKB_REVEAL_SMOKE_FILE").expect("합성 문서 경로를 지정하세요");
        let (path, _) = validate_source(&value).expect("합성 문서 경로 검증 실패");
        show_in_explorer(&path).expect("탐색기 호출 실패");
    }
}
