import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import type { DesktopImportProgress, DesktopSelectionPreview, ExclusionPage } from '../api/desktopBridge'
import { SelectionPreviewDialog } from './SelectionPreviewDialog'

vi.mock('@tauri-apps/api/core', () => ({
  invoke: vi.fn(),
  Channel: class { onmessage = () => undefined },
}))
const entries: ExclusionPage['entries'] = [
  { displayName: '자료/이미지.png', kind: 'FILE', reason: 'UNSUPPORTED' },
  { displayName: '빈.pdf', kind: 'FILE', reason: 'EMPTY' },
  { displayName: '.임시', kind: 'FOLDER', reason: 'HIDDEN_SYSTEM_OR_TEMPORARY' },
  { displayName: '대용량.pptx', kind: 'FILE', reason: 'TOO_LARGE' },
]
const preview: DesktopSelectionPreview = {
  selectionId: 'selection-fixture', mode: 'FOLDER', rootName: '검증 자료', candidateCount: 1,
  totalBytes: 3_174, excludedCount: 4, maxFileBytes: 26_214_400,
  exclusions: { unsupportedFormat: 1, emptyFile: 1, tooLarge: 1, hiddenSystemOrTemporary: 1, reparsePoint: 0, inaccessible: 0, depthExceeded: 0 },
  entries: [{ displayName: '회의록.txt', byteSize: 3_174 }],
  excludedPage: { entries, page: 0, pageSize: 50, totalElements: 4, hasNext: false },
}

describe('업로드 확인 화면', () => {
  beforeEach(() => { vi.useRealTimers(); vi.mocked(invoke).mockReset() })
  afterEach(() => { cleanup(); vi.useRealTimers() })
  const show = (value = preview, importing = false, progress: DesktopImportProgress | null = null) => {
    const onConfirm = vi.fn(), onCancel = vi.fn()
    return { ...render(<SelectionPreviewDialog key={value.selectionId} preview={value} importing={importing} progress={progress} onConfirm={onConfirm} onCancel={onCancel} />), onConfirm, onCancel }
  }
  const paged = { ...preview, excludedCount: 51, excludedPage: { ...preview.excludedPage, totalElements: 51, hasNext: true } }

  it('KB 용량과 제외 파일명·상대 위치·사유를 처음부터 보여준다', () => {
    show()
    expect(screen.getAllByText('3.2KB')).toHaveLength(2)
    const list = screen.getByRole('region', { name: '제외 항목 목록' })
    expect(within(list).getByText('자료/이미지.png')).toBeVisible()
    expect(within(list).getByText('지원하지 않는 형식')).toBeVisible()
    expect(within(list).getByText('폴더 · 하위 항목은 탐색하지 않음')).toBeVisible()
    expect(screen.getByText(/26.2MB \(26,214,400바이트\)/)).toBeVisible()
    expect(invoke).not.toHaveBeenCalled()
  })

  it('다음 페이지에는 선택 ID·페이지 번호만 보내고 재탐색하지 않는다', async () => {
    const user = userEvent.setup()
    vi.mocked(invoke).mockResolvedValue({ entries: [{ displayName: '마지막.zip', kind: 'FILE', reason: 'UNSUPPORTED' }], page: 1, pageSize: 50, totalElements: 51, hasNext: false })
    show(paged)
    await user.click(screen.getByRole('button', { name: '제외 목록 다음 페이지' }))
    expect(invoke).toHaveBeenCalledExactlyOnceWith('get_document_selection_exclusions', { selectionId: 'selection-fixture', page: 1 })
    expect(screen.getByText('마지막.zip')).toBeVisible()
    expect(screen.queryByText('자료/이미지.png')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '제외 목록 다음 페이지' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '제외 목록 이전 페이지' })).toBeEnabled()
  })

  it('조회 실패 시 기존 목록을 유지하고 재시도하며 내부 경로를 노출하지 않는다', async () => {
    const user = userEvent.setup()
    vi.mocked(invoke).mockRejectedValueOnce('C:\\비공개\\오류.txt').mockResolvedValue({ entries: [], page: 1, pageSize: 50, totalElements: 51, hasNext: false })
    show(paged)
    await user.click(screen.getByRole('button', { name: '제외 목록 다음 페이지' }))
    expect(screen.getByRole('alert')).toHaveTextContent('제외 목록을 불러올 수 없습니다.')
    expect(screen.queryByText(/비공개/)).not.toBeInTheDocument()
    expect(screen.getByText('자료/이미지.png')).toBeVisible()
    await user.click(screen.getByRole('button', { name: '다시 시도' }))
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
    expect(invoke).toHaveBeenLastCalledWith('get_document_selection_exclusions', { selectionId: 'selection-fixture', page: 1 })
  })

  it('5초 후 대기를 끝내고 중복 페이지 조회를 막는다', async () => {
    vi.useFakeTimers()
    vi.mocked(invoke).mockReturnValue(new Promise(() => undefined))
    show(paged)
    const next = screen.getByRole('button', { name: '제외 목록 다음 페이지' })
    fireEvent.click(next)
    fireEvent.click(next)
    expect(invoke).toHaveBeenCalledTimes(1)
    expect(next).toBeDisabled()
    await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
    expect(screen.getByRole('alert')).toBeVisible()
    expect(next).toBeEnabled()
  })

  it('전체 제외된 선택은 추가할 수 없지만 제외 목록과 취소는 사용할 수 있다', async () => {
    const result = show({ ...preview, mode: 'FILES', candidateCount: 0, totalBytes: 0, entries: [] })
    expect(screen.getByRole('dialog', { name: '선택한 문서 추가' })).toBeVisible()
    expect(screen.getByRole('button', { name: '0개 문서 추가' })).toBeDisabled()
    expect(screen.getByText('자료/이미지.png')).toBeVisible()
    await userEvent.click(screen.getByRole('button', { name: '취소' }))
    expect(result.onCancel).toHaveBeenCalledTimes(1)
  })

  it('제외가 없으면 제외 영역을 만들지 않고 확인한 문서만 추가한다', async () => {
    const result = show({ ...preview, excludedCount: 0, excludedPage: { entries: [], page: 0, pageSize: 50, totalElements: 0, hasNext: false } })
    expect(screen.queryByRole('region', { name: '제외 항목 목록' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '1개 문서 추가' }))
    expect(result.onConfirm).toHaveBeenCalledTimes(1)
  })

  it('상세 보관 한도를 넘으면 생략 건수를 명시한다', () => {
    show({ ...preview, excludedCount: 10003, excludedPage: { ...preview.excludedPage, totalElements: 10000, hasNext: true } })
    expect(screen.getByText(/최대 10,000개까지 보관/)).toHaveTextContent('나머지 3개')
  })

  it('처리 중에는 페이지 이동과 추가·취소를 잠근다', () => {
    show(paged, true, { requestedCount: 51, completedCount: 17, acceptedCount: 15, duplicateCount: 2, failedCount: 0, stoppedCount: 0, stopReason: null })
    expect(screen.getByRole('progressbar', { name: '파일 등록 진행률' })).toHaveAttribute('aria-valuenow', '17')
    expect(screen.getByText('1단계 · 파일 등록')).toBeVisible()
    expect(screen.getByText('2단계 · 내용 처리')).toBeVisible()
    expect(screen.getByRole('button', { name: '등록 중 17 / 51' })).toBeDisabled()
    for (const button of screen.getAllByRole('button')) expect(button).toBeDisabled()
  })
})
