// 데스크톱 선택기와 서버 업로드 정책이 지원하는 형식만 안내한다.
export const DOCUMENT_FORMATS = [
  { name: 'PDF', extensions: ['.pdf'] },
  { name: 'Word', extensions: ['.doc', '.docx'] },
  { name: 'PowerPoint', extensions: ['.ppt', '.pptx'] },
  { name: 'Excel', extensions: ['.xls', '.xlsx'] },
  { name: '한글', extensions: ['.hwp'], note: 'HWP 5.x' },
  { name: 'Markdown', extensions: ['.md', '.markdown'] },
  { name: '텍스트', extensions: ['.txt'] },
] as const

export const DOCUMENT_ACCEPT = DOCUMENT_FORMATS.flatMap(format => [...format.extensions]).join(',')
export const DOCUMENT_MAX_FILE_BYTES = 25_000_000
export const PROFILE_UPLOAD_BYTES = {
  DISABLED: DOCUMENT_MAX_FILE_BYTES,
  LOW_SPEC: DOCUMENT_MAX_FILE_BYTES,
  STANDARD: 50_000_000,
  HIGH_SPEC: 100_000_000,
} as const

// 구버전 서비스 응답은 기존 고정 상한보다 낮은 기본값으로 처리한다.
export function currentUploadBytes(profile: { maximumUploadBytes?: number }) {
  const value = profile.maximumUploadBytes
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0 && value <= 100_000_000
    ? value : DOCUMENT_MAX_FILE_BYTES
}
