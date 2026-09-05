use crate::runtime::RuntimeSupervisor;
use crate::upload_transport::{copy_exact_document, load_upload_limit, read_response};
use getrandom::getrandom;
use serde::Serialize;
use std::collections::HashMap;
use std::fs::{self, File, OpenOptions};
use std::io::Write;
use std::net::{Ipv4Addr, SocketAddrV4, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::time::{Duration, UNIX_EPOCH};
use tauri::{ipc::Channel, AppHandle, Manager, State};

#[cfg(windows)]
use std::os::windows::ffi::OsStrExt;
#[cfg(windows)]
use std::os::windows::fs::MetadataExt;
#[cfg(windows)]
use std::os::windows::fs::OpenOptionsExt;
#[cfg(windows)]
use windows_sys::Win32::Storage::FileSystem::{GetDiskFreeSpaceExW, GetDriveTypeW, FILE_SHARE_READ};

const DEFAULT_WORKSPACE_ID: &str = "00000000-0000-0000-0000-000000000001";
static IMPORT_BUSY: AtomicBool = AtomicBool::new(false);
struct ImportGuard;
impl Drop for ImportGuard {
    fn drop(&mut self) { IMPORT_BUSY.store(false, Ordering::Release); }
}

struct StorageCapacityReservation<'a> {
    reserved: &'a AtomicU64,
    bytes: u64,
}

impl Drop for StorageCapacityReservation<'_> {
    fn drop(&mut self) {
        self.reserved.fetch_sub(self.bytes, Ordering::AcqRel);
    }
}
const MAX_TOTAL_BYTES: u64 = 10 * 1024 * 1024 * 1024;
const MAX_FILES: usize = 10_000;
const MAX_VISITED_ENTRIES: usize = 100_000;
const MAX_FOLDER_DEPTH: usize = 32;
const MAX_PREVIEW_ENTRIES: usize = 12;
const MAX_STORED_SELECTIONS: usize = 8;
const EXCLUSION_PAGE_SIZE: usize = 50;
const MAX_EXCLUSION_DETAILS: usize = 10_000;
const MAX_EXCLUSION_NAME_BYTES: usize = 512;
// 여러 작은 파일의 연결·DB 왕복 시간을 줄이되 로컬 디스크와 앱 서버를 과점유하지 않는다.
const MAX_CONCURRENT_IMPORTS: usize = 3;
const INSUFFICIENT_STORAGE_ERROR: &str = "INSUFFICIENT_STORAGE";
const MINIMUM_FREE_STORAGE_BYTES: u64 = 512 * 1024 * 1024;
const FILE_ATTRIBUTE_HIDDEN: u32 = 0x0000_0002;
const FILE_ATTRIBUTE_SYSTEM: u32 = 0x0000_0004;
const FILE_ATTRIBUTE_TEMPORARY: u32 = 0x0000_0100;
const FILE_ATTRIBUTE_REPARSE_POINT: u32 = 0x0000_0400;
#[cfg(windows)]
const DRIVE_UNKNOWN: u32 = 0;
#[cfg(windows)]
const DRIVE_NO_ROOT_DIR: u32 = 1;
#[cfg(windows)]
const DRIVE_REMOTE: u32 = 4;

const SUPPORTED_EXTENSIONS: &[&str] = &[
    "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "hwp", "md", "markdown", "txt",
];

#[derive(Default)]
pub(crate) struct DocumentSelectionStore {
    selections: Arc<Mutex<HashMap<String, DocumentSelection>>>,
}

#[derive(Clone)]
struct DocumentSelection {
    root: Option<PathBuf>,
    candidates: Vec<DocumentCandidate>,
    excluded_entries: Arc<Vec<ExcludedEntry>>,
    max_file_bytes: u64,
}

#[derive(Clone)]
struct DocumentCandidate {
    path: PathBuf,
    relative_path: Option<String>,
    byte_size: u64,
    last_modified_millis: u64,
}

#[derive(Clone, Copy)]
enum SelectionMode {
    Files,
    Folder,
}

impl SelectionMode {
    fn as_str(self) -> &'static str {
        match self {
            Self::Files => "FILES",
            Self::Folder => "FOLDER",
        }
    }
}

#[derive(Default, Serialize)]
#[serde(rename_all = "camelCase")]
struct ExclusionSummary {
    unsupported_format: usize,
    empty_file: usize,
    too_large: usize,
    hidden_system_or_temporary: usize,
    reparse_point: usize,
    inaccessible: usize,
    depth_exceeded: usize,
}

impl ExclusionSummary {
    fn total(&self) -> usize {
        self.unsupported_format
            + self.empty_file
            + self.too_large
            + self.hidden_system_or_temporary
            + self.reparse_point
            + self.inaccessible
            + self.depth_exceeded
    }
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct ExcludedEntry {
    display_name: String,
    kind: ExcludedKind,
    reason: ExclusionReason,
}

#[derive(Clone, Copy, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum ExcludedKind { File, Folder, Unknown }

#[derive(Default)]
struct ExclusionReport {
    summary: ExclusionSummary,
    entries: Vec<ExcludedEntry>,
}

impl ExclusionReport {
    fn record(&mut self, path: &Path, root: Option<&Path>, kind: ExcludedKind, reason: ExclusionReason) {
        reason.increment(&mut self.summary);
        // 이름은 화면 확인용으로만 제한해 보관한다. 제외 파일을 추가로 읽거나 재탐색하지 않는다.
        if self.entries.len() < MAX_EXCLUSION_DETAILS {
            self.entries.push(ExcludedEntry { display_name: exclusion_display_name(path, root), kind, reason });
        }
    }
}

fn exclusion_display_name(path: &Path, root: Option<&Path>) -> String {
    let relative = root.and_then(|root| path.strip_prefix(root).ok()).filter(|path| !path.as_os_str().is_empty());
    let raw = relative.map(|path| path.to_string_lossy().into_owned()).unwrap_or_else(|| file_name(path));
    let mut result = String::new();
    for ch in raw.chars() {
        let ch = if ch.is_control() { '�' } else { ch };
        if result.len() + ch.len_utf8() > MAX_EXCLUSION_NAME_BYTES {
            result.push('…');
            break;
        }
        result.push(ch);
    }
    result
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ExclusionPage {
    entries: Vec<ExcludedEntry>,
    page: usize,
    page_size: usize,
    total_elements: usize,
    has_next: bool,
}

fn exclusion_page(entries: &[ExcludedEntry], page: usize) -> Result<ExclusionPage, String> {
    let start = page.checked_mul(EXCLUSION_PAGE_SIZE).ok_or("제외 목록 페이지가 올바르지 않습니다.")?;
    if (start >= entries.len() && page != 0) || start > entries.len() {
        return Err("제외 목록 페이지가 올바르지 않습니다.".to_owned());
    }
    let end = (start + EXCLUSION_PAGE_SIZE).min(entries.len());
    Ok(ExclusionPage { entries: entries[start..end].to_vec(), page, page_size: EXCLUSION_PAGE_SIZE,
        total_elements: entries.len(), has_next: end < entries.len() })
}

#[tauri::command]
pub(crate) fn get_document_selection_exclusions(
    selection_id: String, page: usize, selections: State<'_, DocumentSelectionStore>,
) -> Result<ExclusionPage, String> {
    let stored = lock_or_recover(&selections.selections);
    let selection = stored.get(&selection_id).ok_or("문서 선택 정보가 만료되었습니다. 다시 선택해 주세요.")?;
    exclusion_page(&selection.excluded_entries, page)
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct DocumentSelectionPreview {
    selection_id: String,
    mode: &'static str,
    root_name: Option<String>,
    candidate_count: usize,
    total_bytes: u64,
    excluded_count: usize,
    exclusions: ExclusionSummary,
    excluded_page: ExclusionPage,
    max_file_bytes: u64,
    entries: Vec<PreviewEntry>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PreviewEntry {
    display_name: String,
    byte_size: u64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct DesktopImportResult {
    requested_count: usize,
    accepted_count: usize,
    duplicate_count: usize,
    failed_count: usize,
    stopped_count: usize,
    stop_reason: Option<&'static str>,
}

#[derive(Clone, Copy, Default)]
struct ImportCounts {
    accepted_count: usize,
    duplicate_count: usize,
    failed_count: usize,
}

impl ImportCounts {
    fn completed_count(self) -> usize {
        self.accepted_count + self.duplicate_count + self.failed_count
    }

    fn record(&mut self, disposition: Result<ImportDisposition, String>) {
        match disposition {
            Ok(ImportDisposition::Accepted) => self.accepted_count += 1,
            Ok(ImportDisposition::Duplicate) => self.duplicate_count += 1,
            Err(_) => self.failed_count += 1,
        }
    }

    fn progress(
        self,
        requested_count: usize,
        stopped_count: usize,
        stop_reason: Option<&'static str>,
    ) -> DesktopImportProgress {
        DesktopImportProgress {
            requested_count,
            completed_count: self.completed_count(),
            accepted_count: self.accepted_count,
            duplicate_count: self.duplicate_count,
            failed_count: self.failed_count,
            stopped_count,
            stop_reason,
        }
    }
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct DesktopImportProgress {
    requested_count: usize,
    completed_count: usize,
    accepted_count: usize,
    duplicate_count: usize,
    failed_count: usize,
    stopped_count: usize,
    stop_reason: Option<&'static str>,
}

#[tauri::command]
pub(crate) fn select_document_files(
    app: AppHandle,
    selections: State<'_, DocumentSelectionStore>,
    runtime: State<'_, RuntimeSupervisor>,
) -> Result<Option<DocumentSelectionPreview>, String> {
    let bridge = runtime.bridge_connection()?;
    let max_file_bytes = load_upload_limit(bridge.backend_port)?;
    let picked = rfd::FileDialog::new()
        .set_title("PrivateKB에 추가할 문서 선택")
        .add_filter("지원 문서", SUPPORTED_EXTENSIONS)
        .pick_files();
    let Some(paths) = picked else {
        return Ok(None);
    };
    let managed_root = managed_storage_root(&app);
    let mut exclusions = ExclusionReport::default();
    if paths.len() > MAX_VISITED_ENTRIES {
        return Err("선택한 항목 수가 안전 탐색 한도를 초과했습니다.".to_owned());
    }
    let mut candidates = Vec::new();
    for path in paths {
        match prepare_candidate(&path, None, managed_root.as_deref(), max_file_bytes) {
            Ok(candidate) => candidates.push(candidate),
            Err(reason) => exclusions.record(&path, None, ExcludedKind::File, reason),
        }
    }
    create_preview(
        &selections,
        SelectionMode::Files,
        None,
        candidates,
        exclusions,
        max_file_bytes,
    )
    .map(Some)
}

#[tauri::command]
pub(crate) fn select_document_folder(
    app: AppHandle,
    selections: State<'_, DocumentSelectionStore>,
    runtime: State<'_, RuntimeSupervisor>,
) -> Result<Option<DocumentSelectionPreview>, String> {
    let bridge = runtime.bridge_connection()?;
    let max_file_bytes = load_upload_limit(bridge.backend_port)?;
    let Some(picked_root) = rfd::FileDialog::new()
        .set_title("PrivateKB에 추가할 폴더 선택")
        .pick_folder()
    else {
        return Ok(None);
    };
    require_safe_selected_root(&picked_root)?;
    let root = windows_user_path(
        picked_root
            .canonicalize()
            .map_err(|_| "선택한 폴더 위치를 확인할 수 없습니다.".to_owned())?,
    );
    let managed_root = managed_storage_root(&app);
    if managed_root
        .as_deref()
        .is_some_and(|managed| root.starts_with(managed))
    {
        return Err("PrivateKB 관리 저장소는 수집 폴더로 선택할 수 없습니다.".to_owned());
    }
    let (candidates, exclusions) = scan_folder(&root, managed_root.as_deref(), max_file_bytes)?;
    create_preview(
        &selections,
        SelectionMode::Folder,
        Some(root),
        candidates,
        exclusions,
        max_file_bytes,
    )
    .map(Some)
}

#[tauri::command]
pub(crate) fn discard_document_selection(
    selection_id: String,
    selections: State<'_, DocumentSelectionStore>,
) -> Result<(), String> {
    lock_or_recover(&selections.selections).remove(&selection_id);
    Ok(())
}

#[tauri::command]
pub(crate) async fn import_document_selection(
    app: AppHandle,
    selection_id: String,
    on_progress: Channel<DesktopImportProgress>,
    selections: State<'_, DocumentSelectionStore>,
    runtime: State<'_, RuntimeSupervisor>,
) -> Result<DesktopImportResult, String> {
    let selection = lock_or_recover(&selections.selections)
        .get(&selection_id)
        .cloned()
        .ok_or_else(|| "문서 선택 정보가 만료되었습니다. 다시 선택해 주세요.".to_owned())?;
    let bridge = runtime.bridge_connection()?;
    let storage_capacity_root = managed_storage_root(&app)
        .ok_or_else(|| "PrivateKB 저장 위치를 확인할 수 없습니다.".to_owned())?;
    let stored_selections = Arc::clone(&selections.selections);
    IMPORT_BUSY.compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
        .map_err(|_| "다른 문서 추가 작업을 처리하고 있습니다.".to_owned())?;
    let guard = ImportGuard;
    tauri::async_runtime::spawn_blocking(move || {
    let _guard = guard;
    let current_limit = load_upload_limit(bridge.backend_port)?;
    if current_limit != selection.max_file_bytes {
        return Err("사양 설정이 변경되었습니다. 현재 파일 제한으로 문서나 폴더를 다시 선택해 주세요.".to_owned());
    }
    let requested_count = selection.candidates.len();
    let counts = Arc::new(Mutex::new(ImportCounts::default()));
    let next_index = AtomicUsize::new(0);
    let reserved_source_bytes = Arc::new(AtomicU64::new(0));
    let stop_requested = Arc::new(AtomicBool::new(false));
    let _ = on_progress.send(ImportCounts::default().progress(requested_count, 0, None));
    let worker_count = requested_count.min(MAX_CONCURRENT_IMPORTS);

    std::thread::scope(|scope| {
        for _ in 0..worker_count {
            let counts = Arc::clone(&counts);
            let on_progress = on_progress.clone();
            let selection = &selection;
            let bridge = &bridge;
            let next_index = &next_index;
            let stop_requested = Arc::clone(&stop_requested);
            let reserved_source_bytes = Arc::clone(&reserved_source_bytes);
            let storage_capacity_root = &storage_capacity_root;
            scope.spawn(move || loop {
                if stop_requested.load(Ordering::Acquire) {
                    break;
                }
                let index = next_index.fetch_add(1, Ordering::Relaxed);
                let Some(candidate) = selection.candidates.get(index) else {
                    break;
                };
                let result = match reserve_storage_capacity(
                    storage_capacity_root,
                    candidate.byte_size,
                    &reserved_source_bytes,
                ) {
                    Ok(_reservation) => import_candidate(
                        candidate,
                        selection.root.as_deref(),
                        bridge.backend_port,
                        &bridge.token,
                    ),
                    Err(error) => Err(error),
                };
                let insufficient_storage = matches!(
                    &result,
                    Err(error) if error == INSUFFICIENT_STORAGE_ERROR
                );
                if insufficient_storage {
                    stop_requested.store(true, Ordering::Release);
                }
                let progress = {
                    let mut current = lock_or_recover(&counts);
                    current.record(result);
                    current.progress(requested_count, 0, None)
                };
                let _ = on_progress.send(progress);
            });
        }
    });

    let final_counts = *lock_or_recover(&counts);
    let accepted_count = final_counts.accepted_count;
    let duplicate_count = final_counts.duplicate_count;
    let failed_count = final_counts.failed_count;
    let stopped_for_storage = stop_requested.load(Ordering::Acquire);
    let stopped_count = if stopped_for_storage {
        requested_count.saturating_sub(final_counts.completed_count())
    } else {
        0
    };
    let stop_reason = stopped_for_storage.then_some(INSUFFICIENT_STORAGE_ERROR);
    let _ = on_progress.send(final_counts.progress(
        requested_count,
        stopped_count,
        stop_reason,
    ));
    if failed_count == 0 {
        lock_or_recover(&stored_selections).remove(&selection_id);
    }
    Ok(DesktopImportResult {
        requested_count,
        accepted_count,
        duplicate_count,
        failed_count,
        stopped_count,
        stop_reason,
    })
    }).await.map_err(|_| "문서 추가 작업을 완료할 수 없습니다.".to_owned())?
}

fn create_preview(
    selections: &DocumentSelectionStore,
    mode: SelectionMode,
    root: Option<PathBuf>,
    candidates: Vec<DocumentCandidate>,
    exclusions: ExclusionReport,
    max_file_bytes: u64,
) -> Result<DocumentSelectionPreview, String> {
    if candidates.len() > MAX_FILES {
        return Err(format!(
            "한 번에 최대 {MAX_FILES}개 문서만 추가할 수 있습니다."
        ));
    }
    let total_bytes = candidates
        .iter()
        .try_fold(0_u64, |total, candidate| {
            total.checked_add(candidate.byte_size)
        })
        .ok_or_else(|| "선택한 문서의 전체 크기를 계산할 수 없습니다.".to_owned())?;
    if total_bytes > MAX_TOTAL_BYTES {
        return Err(format!("한 번에 추가할 수 있는 전체 문서 크기 약 {:.2}GB를 초과했습니다.", MAX_TOTAL_BYTES as f64 / 1_000_000_000.0));
    }
    let entries = candidates
        .iter()
        .take(MAX_PREVIEW_ENTRIES)
        .map(|candidate| PreviewEntry {
            display_name: candidate
                .relative_path
                .clone()
                .unwrap_or_else(|| file_name(&candidate.path)),
            byte_size: candidate.byte_size,
        })
        .collect();
    let root_name = root.as_deref().map(file_name);
    let selection_id = generate_selection_id()?;
    let preview = DocumentSelectionPreview {
        selection_id: selection_id.clone(),
        mode: mode.as_str(),
        root_name,
        candidate_count: candidates.len(),
        total_bytes,
        excluded_count: exclusions.summary.total(),
        excluded_page: exclusion_page(&exclusions.entries, 0)?,
        max_file_bytes,
        exclusions: exclusions.summary,
        entries,
    };
    let mut stored = lock_or_recover(&selections.selections);
    if stored.len() >= MAX_STORED_SELECTIONS {
        if let Some(expired) = stored.keys().next().cloned() {
            stored.remove(&expired);
        }
    }
    stored.insert(selection_id, DocumentSelection { root, candidates, excluded_entries: Arc::new(exclusions.entries), max_file_bytes });
    Ok(preview)
}

fn scan_folder(
    root: &Path,
    managed_root: Option<&Path>,
    max_file_bytes: u64,
) -> Result<(Vec<DocumentCandidate>, ExclusionReport), String> {
    let mut candidates = Vec::new();
    let mut exclusions = ExclusionReport::default();
    let mut directories = vec![(root.to_path_buf(), 0_usize)];
    let mut visited = 0_usize;
    while let Some((directory, depth)) = directories.pop() {
        let entries = match fs::read_dir(&directory) {
            Ok(entries) => entries,
            Err(_) => {
                exclusions.record(&directory, Some(root), ExcludedKind::Folder, ExclusionReason::Inaccessible);
                continue;
            }
        };
        for entry in entries {
            visited += 1;
            if visited > MAX_VISITED_ENTRIES {
                return Err("선택한 폴더의 항목 수가 안전 탐색 한도를 초과했습니다.".to_owned());
            }
            let Ok(entry) = entry else {
                exclusions.record(&directory.join("(항목 이름 확인 불가)"), Some(root), ExcludedKind::Unknown, ExclusionReason::Inaccessible);
                continue;
            };
            let path = entry.path();
            let Ok(metadata) = fs::symlink_metadata(&path) else {
                exclusions.record(&path, Some(root), ExcludedKind::Unknown, ExclusionReason::Inaccessible);
                continue;
            };
            let kind = if metadata.is_dir() { ExcludedKind::Folder } else { ExcludedKind::File };
            if is_reparse_or_symlink(&metadata) {
                exclusions.record(&path, Some(root), kind, ExclusionReason::ReparsePoint);
                continue;
            }
            if is_hidden_system_or_temporary(&path, &metadata) {
                exclusions.record(&path, Some(root), kind, ExclusionReason::HiddenSystemOrTemporary);
                continue;
            }
            if metadata.is_dir() {
                if depth >= MAX_FOLDER_DEPTH {
                    exclusions.record(&path, Some(root), kind, ExclusionReason::DepthExceeded);
                } else {
                    directories.push((path, depth + 1));
                }
                continue;
            }
            match prepare_candidate(&path, Some(root), managed_root, max_file_bytes) {
                Ok(candidate) => candidates.push(candidate),
                Err(reason) => exclusions.record(&path, Some(root), kind, reason),
            }
            if candidates.len() > MAX_FILES {
                return Err(format!(
                    "한 번에 최대 {MAX_FILES}개 문서만 추가할 수 있습니다."
                ));
            }
        }
    }
    candidates.sort_by(|left, right| left.path.cmp(&right.path));
    Ok((candidates, exclusions))
}

#[derive(Clone, Copy, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum ExclusionReason {
    Unsupported,
    Empty,
    TooLarge,
    HiddenSystemOrTemporary,
    ReparsePoint,
    Inaccessible,
    DepthExceeded,
}

impl ExclusionReason {
    fn increment(self, summary: &mut ExclusionSummary) {
        match self {
            Self::Unsupported => summary.unsupported_format += 1,
            Self::Empty => summary.empty_file += 1,
            Self::TooLarge => summary.too_large += 1,
            Self::HiddenSystemOrTemporary => summary.hidden_system_or_temporary += 1,
            Self::ReparsePoint => summary.reparse_point += 1,
            Self::Inaccessible => summary.inaccessible += 1,
            Self::DepthExceeded => summary.depth_exceeded += 1,
        }
    }
}

fn prepare_candidate(
    path: &Path,
    root: Option<&Path>,
    managed_root: Option<&Path>,
    max_file_bytes: u64,
) -> Result<DocumentCandidate, ExclusionReason> {
    let metadata = fs::symlink_metadata(path).map_err(|_| ExclusionReason::Inaccessible)?;
    if is_reparse_or_symlink(&metadata) {
        return Err(ExclusionReason::ReparsePoint);
    }
    if is_hidden_system_or_temporary(path, &metadata) {
        return Err(ExclusionReason::HiddenSystemOrTemporary);
    }
    if !metadata.is_file() {
        return Err(ExclusionReason::Unsupported);
    }
    if !has_supported_extension(path) {
        return Err(ExclusionReason::Unsupported);
    }
    if metadata.len() == 0 {
        return Err(ExclusionReason::Empty);
    }
    if metadata.len() > max_file_bytes {
        return Err(ExclusionReason::TooLarge);
    }
    let canonical = windows_user_path(
        path.canonicalize()
            .map_err(|_| ExclusionReason::Inaccessible)?,
    );
    if !is_local_path(&canonical)
        || managed_root.is_some_and(|managed| canonical.starts_with(managed))
    {
        return Err(ExclusionReason::Inaccessible);
    }
    let modified = metadata
        .modified()
        .ok()
        .and_then(|value| value.duration_since(UNIX_EPOCH).ok())
        .ok_or(ExclusionReason::Inaccessible)?
        .as_millis();
    let last_modified_millis =
        u64::try_from(modified).map_err(|_| ExclusionReason::Inaccessible)?;
    let relative_path = root
        .map(|root| {
            canonical
                .strip_prefix(root)
                .map_err(|_| ExclusionReason::Inaccessible)
        })
        .transpose()?
        .map(path_to_text)
        .transpose()?;
    Ok(DocumentCandidate {
        path: canonical,
        relative_path,
        byte_size: metadata.len(),
        last_modified_millis,
    })
}

fn require_safe_selected_root(root: &Path) -> Result<(), String> {
    let metadata = fs::symlink_metadata(root)
        .map_err(|_| "선택한 폴더 위치를 확인할 수 없습니다.".to_owned())?;
    if !metadata.is_dir() || is_reparse_or_symlink(&metadata) || !is_local_path(root) {
        return Err("로컬 일반 폴더만 선택할 수 있습니다.".to_owned());
    }
    Ok(())
}

pub(crate) fn has_supported_extension(path: &Path) -> bool {
    path.extension()
        .and_then(|extension| extension.to_str())
        .is_some_and(|extension| {
            SUPPORTED_EXTENSIONS
                .iter()
                .any(|supported| extension.eq_ignore_ascii_case(supported))
        })
}

fn is_hidden_system_or_temporary(path: &Path, metadata: &fs::Metadata) -> bool {
    let filename = path
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or_default();
    if filename.starts_with('.') || filename.starts_with("~$") || filename.ends_with(".tmp") {
        return true;
    }
    #[cfg(windows)]
    {
        let attributes = metadata.file_attributes();
        return attributes
            & (FILE_ATTRIBUTE_HIDDEN | FILE_ATTRIBUTE_SYSTEM | FILE_ATTRIBUTE_TEMPORARY)
            != 0;
    }
    #[cfg(not(windows))]
    {
        let _ = metadata;
        false
    }
}

pub(crate) fn is_reparse_or_symlink(metadata: &fs::Metadata) -> bool {
    if metadata.file_type().is_symlink() {
        return true;
    }
    #[cfg(windows)]
    {
        return metadata.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT != 0;
    }
    #[cfg(not(windows))]
    false
}

pub(crate) fn is_local_path(path: &Path) -> bool {
    if !path.is_absolute() {
        return false;
    }
    let text = path.to_string_lossy();
    if text.starts_with(r"\\") || text.starts_with(r"\\?\") || text.starts_with(r"\\.\") {
        return false;
    }
    #[cfg(windows)]
    {
        let bytes = text.as_bytes();
        if bytes.len() < 3 || bytes[1] != b':' || bytes[2] != b'\\' {
            return false;
        }
        let root = Path::new(&text[..3]);
        let wide: Vec<u16> = root.as_os_str().encode_wide().chain(Some(0)).collect();
        let drive_type = unsafe { GetDriveTypeW(wide.as_ptr()) };
        return drive_type != DRIVE_REMOTE
            && drive_type != DRIVE_UNKNOWN
            && drive_type != DRIVE_NO_ROOT_DIR;
    }
    #[cfg(not(windows))]
    true
}

fn managed_storage_root(app: &AppHandle) -> Option<PathBuf> {
    app.path()
        .app_local_data_dir()
        .ok()
        .and_then(|path| path.canonicalize().ok())
        .map(windows_user_path)
}

pub(crate) fn windows_user_path(path: PathBuf) -> PathBuf {
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

fn path_to_text(path: &Path) -> Result<String, ExclusionReason> {
    path.to_str()
        .map(str::to_owned)
        .ok_or(ExclusionReason::Inaccessible)
}

fn file_name(path: &Path) -> String {
    path.file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("선택한 문서")
        .to_owned()
}

fn generate_selection_id() -> Result<String, String> {
    let mut bytes = [0_u8; 24];
    getrandom(&mut bytes).map_err(|_| "문서 선택 식별자를 만들 수 없습니다.".to_owned())?;
    let mut id = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        use std::fmt::Write as _;
        let _ = write!(&mut id, "{byte:02x}");
    }
    Ok(id)
}

enum ImportDisposition {
    Accepted,
    Duplicate,
}

fn import_candidate(
    candidate: &DocumentCandidate,
    root: Option<&Path>,
    backend_port: u16,
    token: &str,
) -> Result<ImportDisposition, String> {
    let before =
        fs::metadata(&candidate.path).map_err(|_| "선택한 문서를 읽을 수 없습니다.".to_owned())?;
    let before_modified = modified_millis(&before)?;
    if before.len() != candidate.byte_size || before_modified != candidate.last_modified_millis {
        return Err("선택 후 원본 문서가 변경되었습니다.".to_owned());
    }
    let mut file = open_stable_source(&candidate.path)?;
    let opened = file.metadata().map_err(|_| "선택한 문서 상태를 확인할 수 없습니다.".to_owned())?;
    if opened.len() != before.len() || modified_millis(&opened)? != before_modified {
        return Err("문서를 여는 동안 원본이 변경되었습니다.".to_owned());
    }
    let source_path = candidate
        .path
        .to_str()
        .ok_or_else(|| "원본 문서 경로를 처리할 수 없습니다.".to_owned())?;
    let root_path = root
        .map(|path| {
            path.to_str()
                .ok_or_else(|| "선택한 폴더 경로를 처리할 수 없습니다.".to_owned())
        })
        .transpose()?;
    let boundary = format!("----PrivateKBBoundary{}", generate_selection_id()?);
    let mut prefix = Vec::with_capacity(4096);
    append_text_part(
        &mut prefix,
        &boundary,
        "originalFilename",
        &file_name(&candidate.path),
    );
    append_text_part(
        &mut prefix,
        &boundary,
        "declaredMediaType",
        "application/octet-stream",
    );
    append_text_part(
        &mut prefix,
        &boundary,
        "lastModifiedMillis",
        &candidate.last_modified_millis.to_string(),
    );
    append_text_part(&mut prefix, &boundary, "sourcePath", source_path);
    if let Some(root_path) = root_path {
        append_text_part(&mut prefix, &boundary, "sourceRootPath", root_path);
    }
    if let Some(relative_path) = candidate.relative_path.as_deref() {
        append_text_part(&mut prefix, &boundary, "relativePath", relative_path);
    }
    append_file_header(&mut prefix, &boundary);
    let suffix = format!("\r\n--{boundary}--\r\n").into_bytes();
    let disposition = send_import_request(backend_port, token, &boundary, &prefix, &mut file, candidate.byte_size, &suffix)?;
    let after = file.metadata().map_err(|_| "선택한 문서 상태를 확인할 수 없습니다.".to_owned())?;
    if after.len() != before.len() || modified_millis(&after)? != before_modified {
        return Err("문서를 읽는 동안 원본이 변경되었습니다.".to_owned());
    }
    Ok(disposition)
}

fn open_stable_source(path: &Path) -> Result<File, String> {
    let mut options = OpenOptions::new();
    options.read(true);
    #[cfg(windows)]
    options.share_mode(FILE_SHARE_READ);
    options.open(path).map_err(|_| "선택한 문서를 읽을 수 없습니다. 다른 프로그램에서 열거나 저장 중이면 문서를 닫은 뒤 다시 시도해 주세요.".to_owned())
}

fn append_text_part(body: &mut Vec<u8>, boundary: &str, name: &str, value: &str) {
    body.extend_from_slice(format!("--{boundary}\r\n").as_bytes());
    body.extend_from_slice(
        format!("Content-Disposition: form-data; name=\"{name}\"\r\n").as_bytes(),
    );
    body.extend_from_slice(b"Content-Type: text/plain; charset=UTF-8\r\n\r\n");
    body.extend_from_slice(value.as_bytes());
    body.extend_from_slice(b"\r\n");
}

fn append_file_header(body: &mut Vec<u8>, boundary: &str) {
    body.extend_from_slice(format!("--{boundary}\r\n").as_bytes());
    body.extend_from_slice(
        b"Content-Disposition: form-data; name=\"file\"; filename=\"document.bin\"\r\n",
    );
    body.extend_from_slice(b"Content-Type: application/octet-stream\r\n\r\n");
}

fn send_import_request(
    port: u16,
    token: &str,
    boundary: &str,
    prefix: &[u8],
    input: &mut impl std::io::Read,
    input_bytes: u64,
    suffix: &[u8],
) -> Result<ImportDisposition, String> {
    let address = SocketAddrV4::new(Ipv4Addr::LOCALHOST, port);
    let mut stream = TcpStream::connect_timeout(&address.into(), Duration::from_secs(3))
        .map_err(|_| "PrivateKB 로컬 서비스에 연결할 수 없습니다.".to_owned())?;
    let _ = stream.set_write_timeout(Some(Duration::from_secs(60)));
    let _ = stream.set_read_timeout(Some(Duration::from_secs(120)));
    let body_bytes = (prefix.len() as u64).checked_add(input_bytes)
        .and_then(|value| value.checked_add(suffix.len() as u64))
        .ok_or_else(|| "문서 추가 요청 크기를 계산할 수 없습니다.".to_owned())?;
    let request = format!(
        "POST /api/desktop/workspaces/{DEFAULT_WORKSPACE_ID}/documents HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nX-PrivateKB-Desktop-Token: {token}\r\nContent-Type: multipart/form-data; boundary={boundary}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        body_bytes
    );
    stream.write_all(request.as_bytes())
        .and_then(|_| stream.write_all(prefix))
        .and_then(|_| copy_exact_document(input, &mut stream, input_bytes))
        .and_then(|_| stream.write_all(suffix))
        .map_err(|_| "문서를 전송하는 동안 파일이 변경되었거나 연결이 중단되었습니다.".to_owned())?;
    let response = read_response(&mut stream, Duration::from_secs(120))
        .map_err(|_| "문서 추가 응답을 확인할 수 없습니다.".to_owned())?;
    let status_line = response
        .split(|byte| *byte == b'\n')
        .next()
        .map(String::from_utf8_lossy)
        .unwrap_or_default();
    let status = status_line.split_whitespace().nth(1);
    if status == Some("202") {
        Ok(ImportDisposition::Accepted)
    } else if status == Some("200") {
        Ok(ImportDisposition::Duplicate)
    } else if status == Some("507") {
        Err(INSUFFICIENT_STORAGE_ERROR.to_owned())
    } else {
        Err("선택한 문서를 추가하지 못했습니다.".to_owned())
    }
}

fn modified_millis(metadata: &fs::Metadata) -> Result<u64, String> {
    let value = metadata
        .modified()
        .map_err(|_| "문서 수정 시각을 확인할 수 없습니다.".to_owned())?
        .duration_since(UNIX_EPOCH)
        .map_err(|_| "문서 수정 시각을 처리할 수 없습니다.".to_owned())?
        .as_millis();
    u64::try_from(value).map_err(|_| "문서 수정 시각을 처리할 수 없습니다.".to_owned())
}

fn reserve_storage_capacity<'a>(
    path: &Path,
    bytes: u64,
    reserved: &'a AtomicU64,
) -> Result<StorageCapacityReservation<'a>, String> {
    loop {
        let current = reserved.load(Ordering::Acquire);
        let usable = available_storage_bytes(path)?;
        if !has_storage_capacity(usable, current, bytes) {
            return Err(INSUFFICIENT_STORAGE_ERROR.to_owned());
        }
        let next = current.checked_add(bytes)
            .ok_or_else(|| INSUFFICIENT_STORAGE_ERROR.to_owned())?;
        if reserved.compare_exchange(current, next, Ordering::AcqRel, Ordering::Acquire).is_ok() {
            return Ok(StorageCapacityReservation { reserved, bytes });
        }
    }
}

fn has_storage_capacity(usable: u64, reserved: u64, requested: u64) -> bool {
    usable.checked_sub(MINIMUM_FREE_STORAGE_BYTES)
        .and_then(|available| available.checked_sub(reserved))
        .is_some_and(|available| available >= requested)
}

#[cfg(windows)]
fn available_storage_bytes(path: &Path) -> Result<u64, String> {
    let wide: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
    let mut available = 0_u64;
    let succeeded = unsafe {
        GetDiskFreeSpaceExW(
            wide.as_ptr(),
            &mut available,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
        )
    };
    if succeeded == 0 {
        return Err("PrivateKB 저장 공간을 확인할 수 없습니다.".to_owned());
    }
    Ok(available)
}

#[cfg(not(windows))]
fn available_storage_bytes(_: &Path) -> Result<u64, String> {
    Ok(u64::MAX)
}

fn lock_or_recover<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

#[cfg(test)]
mod tests {
    use super::{
        create_preview, has_supported_extension, scan_folder, DocumentSelectionStore,
        ExclusionReport, ExclusionReason, ExcludedKind, SelectionMode,
    };
    use std::fs;
    use std::path::PathBuf;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn test_directory(label: &str) -> PathBuf {
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("시각 확인")
            .as_nanos();
        std::env::temp_dir().join(format!("privatekb-{label}-{unique}"))
    }

    #[test]
    fn supported_extensions_ignore_letter_case() {
        assert!(has_supported_extension(&PathBuf::from("회의록.PDF")));
        assert!(has_supported_extension(&PathBuf::from("분석.XLSX")));
        assert!(!has_supported_extension(&PathBuf::from("실행.exe")));
        assert!(!has_supported_extension(&PathBuf::from("문서.hwpx")));
    }

    #[test]
    fn import_progress_counts_every_terminal_disposition() {
        let mut counts = super::ImportCounts::default();
        counts.record(Ok(super::ImportDisposition::Accepted));
        counts.record(Ok(super::ImportDisposition::Duplicate));
        counts.record(Err("합성 실패".to_owned()));

        let progress = counts.progress(5, 2, Some(super::INSUFFICIENT_STORAGE_ERROR));
        assert_eq!(progress.requested_count, 5);
        assert_eq!(progress.completed_count, 3);
        assert_eq!(progress.accepted_count, 1);
        assert_eq!(progress.duplicate_count, 1);
        assert_eq!(progress.failed_count, 1);
        assert_eq!(progress.stopped_count, 2);
        assert_eq!(progress.stop_reason, Some(super::INSUFFICIENT_STORAGE_ERROR));
    }

    #[test]
    fn storage_capacity_preserves_safety_margin_and_active_reservations() {
        let margin = super::MINIMUM_FREE_STORAGE_BYTES;
        assert!(super::has_storage_capacity(margin + 300, 100, 200));
        assert!(!super::has_storage_capacity(margin + 299, 100, 200));
        assert!(!super::has_storage_capacity(margin - 1, 0, 1));
    }

    #[test]
    fn folder_scan_collects_supported_files_and_counts_exclusions() {
        let directory = test_directory("folder-selection");
        fs::create_dir_all(directory.join("하위")).expect("시험 폴더 생성");
        fs::write(directory.join("회의록.txt"), "회의 내용").expect("텍스트 생성");
        fs::write(directory.join("하위").join("분석.md"), "# 분석").expect("마크다운 생성");
        fs::write(directory.join("제외.exe"), b"binary").expect("제외 파일 생성");
        fs::write(directory.join("빈.pdf"), b"").expect("빈 파일 생성");

        let (candidates, exclusions) = scan_folder(&directory, None, 25_000_000).expect("폴더 탐색");

        assert_eq!(candidates.len(), 2);
        assert_eq!(exclusions.summary.unsupported_format, 1);
        assert_eq!(exclusions.summary.empty_file, 1);
        assert!(exclusions.entries.iter().any(|entry| entry.display_name == "제외.exe" && matches!(entry.reason, ExclusionReason::Unsupported)));
        assert!(exclusions.entries.iter().any(|entry| entry.display_name == "빈.pdf" && matches!(entry.reason, ExclusionReason::Empty)));
        fs::remove_dir_all(directory).expect("시험 폴더 삭제");
    }

    #[test]
    fn preview_rejects_total_over_limit_without_storing_paths() {
        let store = DocumentSelectionStore::default();
        let result = create_preview(
            &store,
            SelectionMode::Files,
            None,
            vec![super::DocumentCandidate {
                path: PathBuf::from("C:\\자료\\대용량.pdf"),
                relative_path: None,
                byte_size: super::MAX_TOTAL_BYTES + 1,
                last_modified_millis: 1,
            }],
            ExclusionReport::default(),
            25_000_000,
        );

        assert!(result.is_err());
        assert!(store.selections.lock().expect("선택 저장소").is_empty());
    }

    #[test]
    fn exclusion_pages_are_bounded_without_absolute_paths_or_rescanning() {
        let root = PathBuf::from(r"C:\비공개\선택한 폴더");
        let mut exclusions = ExclusionReport::default();
        for index in 0..123 {
            exclusions.record(&root.join("자료").join(format!("제외-{index}.exe")), Some(&root), ExcludedKind::File, ExclusionReason::Unsupported);
        }
        let store = DocumentSelectionStore::default();
        let preview = create_preview(&store, SelectionMode::Folder, Some(root), vec![], exclusions, 25_000_000).unwrap();
        assert_eq!(preview.excluded_count, 123);
        assert_eq!(preview.excluded_page.entries.len(), 50);
        assert_eq!(preview.excluded_page.total_elements, 123);
        assert_eq!(preview.max_file_bytes, 25_000_000);
        let text = serde_json::to_string(&preview).unwrap();
        assert!(!text.contains("비공개"));
        let stored = store.selections.lock().unwrap();
        let entries = &stored.get(&preview.selection_id).unwrap().excluded_entries;
        let second = super::exclusion_page(entries, 1).unwrap();
        assert_eq!(second.entries.len(), 50);
        assert!(second.entries[0].display_name.ends_with("제외-50.exe"));
        let last = super::exclusion_page(entries, 2).unwrap();
        assert_eq!(last.entries.len(), 23);
        assert!(!last.has_next);
        assert!(super::exclusion_page(entries, 3).is_err());
        assert!(super::exclusion_page(entries, usize::MAX).is_err());
    }

    #[test]
    fn exclusion_details_have_memory_limits_but_counts_remain_exact() {
        let mut exclusions = ExclusionReport::default();
        let path = PathBuf::from(format!("{}.exe", "한글".repeat(1000)));
        for _ in 0..super::MAX_EXCLUSION_DETAILS + 3 {
            exclusions.record(&path, None, ExcludedKind::File, ExclusionReason::Unsupported);
        }
        assert_eq!(exclusions.summary.total(), super::MAX_EXCLUSION_DETAILS + 3);
        assert_eq!(exclusions.entries.len(), super::MAX_EXCLUSION_DETAILS);
        assert!(exclusions.entries[0].display_name.len() <= super::MAX_EXCLUSION_NAME_BYTES + 3);
        assert!(exclusions.entries[0].display_name.ends_with('…'));
    }

    #[test]
    fn folder_exclusions_keep_relative_names_and_do_not_descend_into_hidden_folders() {
        let directory = test_directory("excluded-folder-details");
        fs::create_dir_all(directory.join("자료")).unwrap();
        fs::create_dir_all(directory.join(".숨김")).unwrap();
        fs::write(directory.join(".숨김").join("탐색하지않음.exe"), b"test").unwrap();
        fs::write(directory.join("자료").join("실행.exe"), b"test").unwrap();
        let large = fs::File::create(directory.join("자료").join("대용량.pdf")).unwrap();
        large.set_len(25_000_001).unwrap();
        drop(large);
        let (candidates, report) = scan_folder(&directory, None, 25_000_000).unwrap();
        assert!(candidates.is_empty());
        assert_eq!(report.summary.total(), 3);
        assert_eq!(report.summary.too_large, 1);
        assert_eq!(report.summary.hidden_system_or_temporary, 1);
        assert!(report.entries.iter().any(|entry| entry.display_name == ".숨김" && matches!(entry.kind, ExcludedKind::Folder)));
        assert!(!report.entries.iter().any(|entry| entry.display_name.contains("탐색하지않음")));
        assert!(report.entries.iter().any(|entry| entry.display_name.ends_with("실행.exe") && entry.display_name.starts_with("자료")));
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn file_selection_names_never_include_parent_directory() {
        let mut report = ExclusionReport::default();
        report.record(&PathBuf::from(r"C:\비공개\빈.pdf"), None, ExcludedKind::File, ExclusionReason::Empty);
        report.record(&PathBuf::from("존재하지않음.pdf"), None, ExcludedKind::Unknown, ExclusionReason::Inaccessible);
        let preview = create_preview(&DocumentSelectionStore::default(), SelectionMode::Files, None, vec![], report, 25_000_000).unwrap();
        assert_eq!(preview.excluded_page.entries[0].display_name, "빈.pdf");
        assert!(matches!(preview.excluded_page.entries[1].reason, ExclusionReason::Inaccessible));
        assert!(!serde_json::to_string(&preview).unwrap().contains("비공개"));
    }

    #[test]
    fn filters_file_and_folder_candidates_at_each_exact_profile_boundary() {
        let directory = test_directory("profile-upload-boundaries");
        fs::create_dir_all(&directory).unwrap();
        let path = directory.join("경계.pdf");
        for maximum in [25_000_000, 50_000_000, 100_000_000] {
            let file = fs::File::create(&path).unwrap();
            file.set_len(maximum).unwrap();
            drop(file);
            assert!(super::prepare_candidate(&path, None, None, maximum).is_ok());
            let (candidates, report) = scan_folder(&directory, None, maximum).unwrap();
            assert_eq!(candidates.len(), 1);
            let preview = create_preview(&DocumentSelectionStore::default(), SelectionMode::Folder, Some(directory.clone()), candidates, report, maximum).unwrap();
            assert_eq!(preview.max_file_bytes, maximum);
            let file = fs::OpenOptions::new().write(true).open(&path).unwrap();
            file.set_len(maximum + 1).unwrap();
            drop(file);
            assert!(matches!(super::prepare_candidate(&path, None, None, maximum), Err(ExclusionReason::TooLarge)));
            let (candidates, report) = scan_folder(&directory, None, maximum).unwrap();
            assert!(candidates.is_empty());
            assert_eq!(report.summary.too_large, 1);
        }
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn streamed_multipart_keeps_content_length_file_bytes_and_duplicate_result() {
        use std::io::{Read, Write};
        use std::net::TcpListener;
        let directory = test_directory("streamed-import");
        fs::create_dir_all(&directory).unwrap();
        let path = directory.join("회의록.txt");
        let content = "회의 내용\r\n한국어 본문".as_bytes();
        fs::write(&path, content).unwrap();
        for status in [202, 200, 507] {
            let listener = TcpListener::bind("127.0.0.1:0").unwrap();
            let port = listener.local_addr().unwrap().port();
            let receiver = std::thread::spawn(move || {
                let (mut socket, _) = listener.accept().unwrap();
                socket.set_read_timeout(Some(std::time::Duration::from_secs(5))).unwrap();
                let mut headers = Vec::new();
                while !headers.ends_with(b"\r\n\r\n") {
                    let mut byte = [0]; socket.read_exact(&mut byte).unwrap(); headers.push(byte[0]);
                }
                let headers = String::from_utf8(headers).unwrap();
                assert!(headers.contains("X-PrivateKB-Desktop-Token: synthetic-token\r\n"));
                let size: usize = headers.lines().find_map(|line| line.strip_prefix("Content-Length: ")).unwrap().parse().unwrap();
                let mut body = vec![0; size]; socket.read_exact(&mut body).unwrap();
                socket.write_all(format!("HTTP/1.0 {status} OK\r\nContent-Length: 2\r\n\r\n{{}}").as_bytes()).unwrap();
                body
            });
            let candidate = super::prepare_candidate(&path, None, None, 25_000_000).unwrap_or_else(|_| panic!("합성 파일 준비 실패"));
            let result = super::import_candidate(&candidate, None, port, "synthetic-token");
            match status {
                202 => assert!(matches!(result, Ok(super::ImportDisposition::Accepted))),
                200 => assert!(matches!(result, Ok(super::ImportDisposition::Duplicate))),
                507 => assert!(matches!(result, Err(ref error) if error == super::INSUFFICIENT_STORAGE_ERROR)),
                _ => unreachable!(),
            }
            let body = receiver.join().unwrap();
            assert!(body.windows(content.len()).any(|bytes| bytes == content));
            assert!(body.ends_with(b"--\r\n"));
            assert!(String::from_utf8_lossy(&body).contains("filename=\"document.bin\""));
        }
        fs::remove_dir_all(directory).unwrap();
    }
}
