import { Channel, invoke } from '@tauri-apps/api/core'
import { configureDesktopApiPort } from './base'

export type ExclusionReason = 'UNSUPPORTED' | 'EMPTY' | 'TOO_LARGE' | 'HIDDEN_SYSTEM_OR_TEMPORARY' | 'REPARSE_POINT' | 'INACCESSIBLE' | 'DEPTH_EXCEEDED'

export interface ExclusionPage {
  entries: Array<{ displayName: string; kind: 'FILE' | 'FOLDER' | 'UNKNOWN'; reason: ExclusionReason }>
  page: number
  pageSize: number
  totalElements: number
  hasNext: boolean
}

export interface DesktopSelectionPreview {
  selectionId: string
  mode: 'FILES' | 'FOLDER'
  rootName: string | null
  candidateCount: number
  totalBytes: number
  excludedCount: number
  exclusions: {
    unsupportedFormat: number
    emptyFile: number
    tooLarge: number
    hiddenSystemOrTemporary: number
    reparsePoint: number
    inaccessible: number
    depthExceeded: number
  }
  excludedPage: ExclusionPage
  maxFileBytes: number
  entries: Array<{
    displayName: string
    byteSize: number
  }>
}

export interface DesktopImportResult {
  requestedCount: number
  acceptedCount: number
  duplicateCount: number
  failedCount: number
  stoppedCount: number
  stopReason: 'INSUFFICIENT_STORAGE' | null
}

export interface DesktopImportProgress extends DesktopImportResult {
  completedCount: number
}

export type DesktopRuntimePhase = 'EXTERNAL' | 'STARTING' | 'READY' | 'ERROR' | 'STOPPED'

export interface DesktopRuntimeStatus {
  phase: DesktopRuntimePhase
  errorCode: string | null
  backendPort: number | null
}

export function isDesktopShell(): boolean {
  return typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window
}

export async function getDesktopRuntimeStatus(): Promise<DesktopRuntimeStatus> {
  const status = await invoke<DesktopRuntimeStatus>('get_runtime_status')
  if (status.phase === 'READY' && status.backendPort !== null) {
    configureDesktopApiPort(status.backendPort)
  }
  return status
}

export async function selectDesktopFiles(): Promise<DesktopSelectionPreview | null> {
  return invoke<DesktopSelectionPreview | null>('select_document_files')
}

export async function selectDesktopFolder(): Promise<DesktopSelectionPreview | null> {
  return invoke<DesktopSelectionPreview | null>('select_document_folder')
}

export async function importDesktopSelection(
  selectionId: string,
  onProgress: (progress: DesktopImportProgress) => void,
): Promise<DesktopImportResult> {
  const progressChannel = new Channel<DesktopImportProgress>()
  progressChannel.onmessage = onProgress
  return invoke<DesktopImportResult>('import_document_selection', { selectionId, onProgress: progressChannel })
}

export async function discardDesktopSelection(selectionId: string): Promise<void> {
  await invoke('discard_document_selection', { selectionId })
}

export async function getDesktopSelectionExclusions(selectionId: string, page: number): Promise<ExclusionPage> {
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    return await Promise.race([
      invoke<ExclusionPage>('get_document_selection_exclusions', { selectionId, page }),
      new Promise<never>((_, reject) => { timer = setTimeout(() => reject(new Error()), 5000) }),
    ])
  } catch {
    throw new Error('제외 목록을 불러올 수 없습니다. 다시 시도하거나 문서·폴더를 다시 선택해 주세요.')
  } finally {
    if (timer !== undefined) clearTimeout(timer)
  }
}

export interface RevealSourceResult {
  sourceChanged: boolean
}

const revealErrors: Record<string, string> = {
  SOURCE_NOT_RECORDED: '원본 위치가 기록되지 않은 문서입니다. 문서 추가에서 원본 파일을 다시 선택하면 위치를 연결할 수 있습니다.',
  SOURCE_MISSING: '원본 파일을 찾을 수 없습니다. 파일을 이동했다면 문서 추가에서 다시 선택해 주세요.',
  SOURCE_UNAVAILABLE: '파일이나 폴더에 접근할 수 없습니다. 권한과 드라이브 연결을 확인해 주세요.',
  SOURCE_UNSAFE: '보안상 로컬 일반 파일만 열 수 있습니다. 네트워크 위치나 링크 경로는 지원하지 않습니다.',
  APP_NOT_READY: '프로그램이 아직 준비되지 않았습니다. 잠시 후 다시 시도해 주세요.',
  REVEAL_BUSY: '다른 파일의 위치를 확인하고 있습니다. 잠시 후 다시 시도해 주세요.',
  REVEAL_TIMEOUT: '위치 확인이 지연되고 있습니다. 잠시 후 탐색기를 확인해 주세요.',
}

export async function revealDocumentSource(documentVersionId: string): Promise<RevealSourceResult> {
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    // 절대 경로나 서비스 토큰은 웹 화면에 전달하지 않는다.
    return await Promise.race([
      invoke<RevealSourceResult>('reveal_document_source', { documentVersionId }),
      new Promise<never>((_, reject) => { timer = setTimeout(() => reject('REVEAL_TIMEOUT'), 10000) }),
    ])
  } catch (error) {
    throw new Error(typeof error === 'string' && revealErrors[error]
      ? revealErrors[error] : '원본 위치를 열 수 없습니다. 상태를 확인한 뒤 다시 시도해 주세요.')
  } finally {
    if (timer !== undefined) clearTimeout(timer)
  }
}
